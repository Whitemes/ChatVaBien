package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;

public class ChatVaBienServer {
    private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
    private static final int MAX_BUFFER_SIZE = 1024;

    private final Map<String, User> loggedUsers = new ConcurrentHashMap<>();

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    private final SocketChannel mdpChannel;
    private final SelectionKey mdpKey;
    private final Map<Long, Context> pendingAuthRequests = new ConcurrentHashMap<>(); // pour lier réponse -> utilisateur
    private long authIdCounter = 0;

    private final Random random = new Random();

    public ChatVaBienServer(int port, InetSocketAddress mdpAddress) throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.bind(new InetSocketAddress(port));
        this.serverSocketChannel.configureBlocking(false);
        this.selector = Selector.open();
        this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        this.mdpChannel = SocketChannel.open();
        this.mdpChannel.configureBlocking(false);
        this.mdpChannel.connect(mdpAddress);
        this.mdpKey = mdpChannel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
    }

    public void launch() throws IOException {
        while (!Thread.interrupted()) {
            selector.select();
            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                try {
                    if (key.channel() == mdpChannel) {
                        if (key.isConnectable()) {
                            if (mdpChannel.finishConnect()) {
                                logger.info("Connecté à ServerMDP");
                            }
                        }
                        if (key.isReadable()) {
                            handleMDPResponse();
                        }
                        iter.remove();
                        continue;
                    }
                    if (key.isValid() && key.isAcceptable()) {
                        doAccept(key);
                    }
                    if (key.isValid() && key.isReadable()) {
                        doRead(key);
                    }
                    if (key.isValid() && key.isWritable()) {
                        doWrite(key);
                    }
                } catch (IOException e) {
                    System.err.println("Connection closed with client due to error: " + e.getMessage());
                    silentlyClose(key);
                }
                iter.remove();
            }
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        SocketChannel sc = serverSocketChannel.accept();
        if (sc == null) return;
        sc.configureBlocking(false);
        SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ);
        clientKey.attach(new Context(clientKey, loggedUsers, selector));
    }

    private void doRead(SelectionKey key) throws IOException {
        var context = (Context) key.attachment();
        context.doRead();
        if (context.isClosed()) {
            silentlyClose(key);
        }
    }

    private void doWrite(SelectionKey key) throws IOException {
        var context = (Context) key.attachment();
        context.doWrite();
        if (context.isClosed()) {
            silentlyClose(key);
        }
    }

    private void silentlyClose(SelectionKey key) {
        try {
            var context = (Context) key.attachment();
            if (context != null) {
                context.logout();
            }
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    private void handleMDPResponse() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int read = mdpChannel.read(buffer);
        if (read == -1) throw new IOException("Connexion fermée par ServerMDP");
        buffer.flip();
        while (buffer.remaining() >= 9) {
            byte status = buffer.get();
            long id = buffer.getLong();
            Context ctx = pendingAuthRequests.remove(id);
            if (ctx != null) {
                ctx.onMDPResponse(status == 1, id);
            }
        }
    }

    public class Context implements ServerContext{
        private final SocketChannel sc;
        private final SelectionKey key;
        private final Selector selector;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(MAX_BUFFER_SIZE);
        private final Queue<ByteBuffer> queueOut = new ArrayDeque<>();
        private final ByteReader opcodeReader = new ByteReader();
        private final StringReader stringReader = new StringReader();
        private final IpReader ipReader = new IpReader();
        private final LongReader idReader = new LongReader();

        private enum State {WAITING_OPCODE, WAITING_ID, WAITING_IP, WAITING_PEUSDO, WAITING_TARGET_PEUSDO, WAITING_MESSAGE, DONE, ERROR}
        private State state = State.WAITING_OPCODE;
        private byte opcode;
        private String message;
        private String peusdo;
        private String targetPeusdo;
        private long token;
        private Ip clientIp;

        private boolean closed = false;
        private boolean loggedIn = false;

        Context(SelectionKey key, Map<String, User> loggedUsers, Selector selector) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.selector = selector;
        }

        void doRead() throws IOException {
            int read = sc.read(bufferIn);
            if (read == -1) {
                closed = true;
                return;
            }
            bufferIn.flip();
            process();
            bufferIn.compact();
        }

        void process() {
            processIn();
        }

        void doWrite() throws IOException {
            while (!queueOut.isEmpty()) {
                ByteBuffer current = queueOut.peek();
                sc.write(current);
                if (current.hasRemaining()) {
                    break;
                }
                queueOut.remove();
            }
            if (queueOut.isEmpty()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        }

        void queueMessage(ByteBuffer bb) {
            queueOut.add(bb.duplicate());
            updateInterestOps();
        }

        void updateInterestOps() {
            int ops = SelectionKey.OP_READ;
            if (!queueOut.isEmpty()) {
                ops |= SelectionKey.OP_WRITE;
            }
            key.interestOps(ops);
        }

        boolean isClosed() {
            return closed;
        }

        public void handleLogin() {
            if (loggedUsers.containsKey(peusdo)) {
                sendLoginStatus(false);
            } else {
                var newUser = new User(random.nextLong(), peusdo, sc, false);
                loggedUsers.put(newUser.pseudo(), newUser);
                loggedIn = true;
                sendLoginStatus(true);
                broadcastMessage("Server", peusdo + " vient de se connecter.");
            }
        }

        public void handleGetUsers() {
            StringBuilder sb = new StringBuilder();
            for (String pseudo : loggedUsers.keySet()) {
                sb.append(pseudo).append("\n");
            }
            ByteBuffer response = User.ProtocolEncoder.encodeUserList(sb.toString(), OPCODE.CONNECTED_USERS_LIST.getCode());
            queueMessage(response);
        }

        public void handlePrivateRequest() {
            if (!loggedUsers.containsKey(peusdo) || !loggedUsers.containsKey(targetPeusdo)) return;
            User targetUser = loggedUsers.get(targetPeusdo);
            ByteBuffer request = User.ProtocolEncoder.encodePrivateRequest(peusdo, targetPeusdo, OPCODE.REQUEST_PRIVATE.getCode());
            SocketChannel userChannel = targetUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        }

        public void handleOKPrivateRequest() {
            if (!loggedUsers.containsKey(peusdo) || !loggedUsers.containsKey(targetPeusdo)) return;
            User requesterUser = loggedUsers.get(targetPeusdo);
            ByteBuffer request = User.ProtocolEncoder.encodeOKPrivateRequest(peusdo, targetPeusdo, clientIp, token, OPCODE.OK_PRIVATE.getCode());
            SocketChannel userChannel = requesterUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        }

        public void handleKOPrivateRequest() {
            if (!loggedUsers.containsKey(peusdo) || !loggedUsers.containsKey(targetPeusdo)) return;
            User requesterUser = loggedUsers.get(targetPeusdo);
            ByteBuffer request = User.ProtocolEncoder.encodeKOPrivateRequest(peusdo, targetPeusdo, OPCODE.KO_PRIVATE.getCode());
            SocketChannel userChannel = requesterUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        }

        private void sendLoginStatus(boolean accepted) {
            ByteBuffer bb = User.ProtocolEncoder.encodeLoginStatus(
                    accepted,
                    OPCODE.LOGIN_ACCEPTED.getCode(),
                    OPCODE.LOGIN_REFUSED.getCode()
            );
            queueMessage(bb);
        }

        private void logout() {
            if (peusdo != null && loggedIn) {
                loggedUsers.remove(peusdo);
                logger.info("Déconnexion de " + peusdo);
                broadcastMessage("Server", peusdo + " s'est déconnecté.");
                loggedIn = false;
            }
        }

        public void onMDPResponse(boolean success, long id) {
            if (opcode == OPCODE.LOGINAUTH.getCode()) {
                if (success) {
                    handleLogin(); // valide
                } else {
                    sendLoginStatus(false);
                }
            } else if (opcode == OPCODE.LOGIN.getCode()) {
                if (success) {
                    sendLoginStatus(false);
                } else {
                    handleLogin();
                }
            }
        }

        private void processIn() {
            for (; ; ) {
                switch (state) {
                    case WAITING_OPCODE -> {
                        var status = opcodeReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            opcode = opcodeReader.get();
                            opcodeReader.reset();
                            state = State.WAITING_PEUSDO;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_ID -> {
                        var status = idReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            token = idReader.get();
                            idReader.reset();
                            state = State.DONE;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_IP -> {
                        var status = ipReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            clientIp = ipReader.get();
                            ipReader.reset();
                            state = State.WAITING_ID;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_PEUSDO -> {
                        var status = stringReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            peusdo = stringReader.get();
                            stringReader.reset();
                            if (opcode == OPCODE.MESSAGE.getCode()) {
                                state = State.WAITING_MESSAGE;
                            } else if (opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.OK_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
                                state = State.WAITING_TARGET_PEUSDO;
                            } else {
                                state = State.DONE;
                            }
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_TARGET_PEUSDO -> {
                        var status = stringReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            targetPeusdo = stringReader.get();
                            stringReader.reset();
                            if (opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
                                state = State.DONE;
                            } else if (opcode == OPCODE.OK_PRIVATE.getCode()) {
                                state = State.WAITING_IP;
                            }
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_MESSAGE -> {
                        var status = stringReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            message = stringReader.get();
                            stringReader.reset();
                            state = State.DONE;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case DONE -> {
                        Request request = switch (OPCODE.fromCode(opcode)) {
                            case LOGIN -> new LoginRequest();
                            case MESSAGE -> new MessageRequest(peusdo, message);
                            case GET_CONNECTED_USERS -> new GetUsersRequest();
                            case REQUEST_PRIVATE -> new PrivateRequest(peusdo, targetPeusdo);
                            case OK_PRIVATE -> new OKPrivateRequest(peusdo, targetPeusdo, token);
                            case KO_PRIVATE -> new KOPrivateResquest(peusdo, targetPeusdo);
                            default -> null;
                        };

                        if (request != null) {
                            request.handle(this);
                        } else {
                            logger.warning("Unknown or unhandled opcode");
                        }
                        state = State.WAITING_OPCODE;
                    }
                    case ERROR -> {
                        closed = true;
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unexpected value: " + state);
                }
            }
        }

        public void broadcastMessage(String sender, String message) {
            ByteBuffer bb = User.ProtocolEncoder.encodeBroadcastMessage(sender, message, OPCODE.MESSAGE.getCode());
            for (Map.Entry<String, User> entry : loggedUsers.entrySet()) {
                if (!entry.getKey().equals(sender)) {
                    SocketChannel userChannel = entry.getValue().sc();
                    if (userChannel.isOpen()) {
                        SelectionKey userKey = userChannel.keyFor(selector);
                        if (userKey != null) {
                            Context userContext = (Context) userKey.attachment();
                            userContext.queueMessage(bb.duplicate());
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java ChatVaBienServer <serverPort> <mdpPort>");
            return;
        }

        int serverPort = Integer.parseInt(args[0]);
        int mdpPort = Integer.parseInt(args[1]);

        InetSocketAddress mdpAddress = new InetSocketAddress("localhost", mdpPort);
        logger.log(Level.INFO, "Server launched");

        new ChatVaBienServer(serverPort, mdpAddress).launch();
    }
}
