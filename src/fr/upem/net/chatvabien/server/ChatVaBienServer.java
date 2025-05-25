package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.GetUsersRequest;
import fr.upem.net.chatvabien.protocol.Ip;
import fr.upem.net.chatvabien.protocol.IpReader;
import fr.upem.net.chatvabien.protocol.KOPrivateResquest;
import fr.upem.net.chatvabien.protocol.LoginRequest;
import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.MessageRequest;
import fr.upem.net.chatvabien.protocol.OKPrivateRequest;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.Request;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.User;

public class ChatVaBienServer {
	private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
	private static final Charset UTF8 = Charset.forName("UTF8");
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
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
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
        if (sc == null) {
        	return;
        }
        sc.configureBlocking(false);
        SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ);
        clientKey.attach(new Context(clientKey, loggedUsers));
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

   public class Context {
        private final SocketChannel sc;
        private final SelectionKey key;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(MAX_BUFFER_SIZE);
        private final Queue<ByteBuffer> queueOut = new ArrayDeque<>();
        private final ByteReader opcodeReader = new ByteReader();
        private final StringReader stringReader = new StringReader();
        private final IpReader ipReader = new IpReader();
        private final LongReader idReader = new LongReader();
        
        private enum State {WAITING_TOTAL_SIZE, WAITING_OPCODE, WAITING_ID, WAITING_IP, WAITING_PEUSDO, WAITING_TARGET_PEUSDO, WAITING_MESSAGE, DONE, ERROR}
        private State state = State.WAITING_OPCODE;
        private byte opcode;
        private String message;
        private String peusdo;
        private String targetPeusdo;
        private long token;
        private PrivateRequest privateResquest;
        private Ip clientIp;
        

        private boolean closed = false;

        Context(SelectionKey key, Map<String, User> loggedUsers) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
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
            logger.info("<<<<<<<buffer >>>>>>>>" +dumpBufferNum(bb));
            updateInterestOps();
        }

        void updateInterestOps() {
        	if (key.interestOps() == SelectionKey.OP_CONNECT) {
        	  try {
        	    if (!sc.finishConnect()) {
        	      logger.warning("This should be fixed !");
        	      return;
        	    }
        	  } catch (IOException e) {
        	    // ignore
        	  }
        	}
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
                sendLoginStatus(true);
                Request request = new MessageRequest("Server", peusdo + " vient de se connecter.");
                request.handle(this);
            }
        }
        
        public void handleGetUsers() {
        	StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, User> entry : loggedUsers.entrySet()) {
                sb.append(entry.getKey()).append("\n");
            }
            ByteBuffer response = encodeUserList(sb.toString());
            queueMessage(response);
        }
        
        public void handlePrivateRequest() {
        	if(!loggedUsers.containsKey(peusdo)) {
        		logger.log(Level.WARNING, "Requete privée reçu d'un utilisateur non connecté.");
        		return;
        	}
        	if(!loggedUsers.containsKey(targetPeusdo)) {
        		logger.log(Level.WARNING, "Requete privée à destinataire d'un utilisateur non connecté.");
        		return;
        	}
        	
        	logger.log(Level.INFO, "Requete privée reçue de " + peusdo + " à " + targetPeusdo);
        	
        	User targetUser = loggedUsers.get(targetPeusdo);
        	var request = encodePrivateRequest();
        	
        	SocketChannel userChannel = targetUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        	
        }
        
        private ByteBuffer encodePrivateRequest() {
        	var resquesterBytes = UTF8.encode(peusdo);
        	var targetBytes = UTF8.encode(targetPeusdo);
        	ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + resquesterBytes.remaining() + Integer.BYTES + targetBytes.remaining());
        	bb.put(OPCODE.REQUEST_PRIVATE.getCode());
        	bb.putInt(resquesterBytes.remaining());
        	bb.put(resquesterBytes);
        	bb.putInt(targetBytes.remaining());
        	bb.put(targetBytes);
        	bb.flip();
        	return bb;
        }
        
        public void handleOKPrivateRequest() {
        	if(!loggedUsers.containsKey(peusdo)) {
        		logger.log(Level.WARNING, "Requete privée reçu d'un utilisateur non connecté.");
        		return;
        	}
        	if(!loggedUsers.containsKey(targetPeusdo)) {
        		logger.log(Level.WARNING, "Requete privée à destinataire d'un utilisateur non connecté.");
        		return;
        	}
        	
        	logger.log(Level.INFO, "Requete privée acceptée reçue de " + peusdo + " à " + targetPeusdo);
        	
        	User requesterUser = loggedUsers.get(targetPeusdo);
        	var request = encodeOKPrivateRequest();
        	
        	SocketChannel userChannel = requesterUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        }
        
        private ByteBuffer encodeOKPrivateRequest() {
            var requesterBytes = UTF8.encode(peusdo);
            var targetBytes = UTF8.encode(targetPeusdo);

            byte[] rawIp = clientIp.address().getAddress();
            byte ipType = clientIp.version();
            int port = clientIp.port();

            ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES +
                    Integer.BYTES + requesterBytes.remaining() +
                    Integer.BYTES + targetBytes.remaining() +
                    Byte.BYTES + rawIp.length +
                    Integer.BYTES +
                    Long.BYTES);

            bb.put(OPCODE.OK_PRIVATE.getCode());
            bb.putInt(requesterBytes.remaining());
            bb.put(requesterBytes);
            bb.putInt(targetBytes.remaining());
            bb.put(targetBytes);
            bb.put(ipType);
            bb.put(rawIp);
            bb.putInt(port);
            bb.putLong(token);
            bb.flip();
            return bb;
        }

        
        public void handleKOPrivateRequest() {
        	if(!loggedUsers.containsKey(peusdo)) {
        		logger.log(Level.WARNING, "Requete privée reçu d'un utilisateur non connecté.");
        		return;
        	}
        	if(!loggedUsers.containsKey(targetPeusdo)) {
        		logger.log(Level.WARNING, "Requete privée à destinataire d'un utilisateur non connecté.");
        		return;
        	}
        	
        	logger.log(Level.INFO, "Requete privée refusée reçue de " + peusdo + " à " + targetPeusdo);
        	
        	User requesterUser = loggedUsers.get(targetPeusdo);
        	var request = encodeKOPrivateRequest();
        	
        	SocketChannel userChannel = requesterUser.sc();
            if (userChannel.isOpen()) {
                SelectionKey userKey = userChannel.keyFor(selector);
                if (userKey != null) {
                    Context userContext = (Context) userKey.attachment();
                    userContext.queueMessage(request.duplicate());
                }
            }
        }
        
        private ByteBuffer encodeKOPrivateRequest() {
        	var resquesterBytes = UTF8.encode(peusdo);
        	var targetBytes = UTF8.encode(targetPeusdo);
        	
        	ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + 
        			Integer.BYTES + resquesterBytes.remaining() + 
        			Integer.BYTES + targetBytes.remaining());
        	
        	bb.put(OPCODE.KO_PRIVATE.getCode());
        	bb.putInt(resquesterBytes.remaining());
        	bb.put(resquesterBytes);
        	bb.putInt(targetBytes.remaining());
        	bb.put(targetBytes);
        	bb.flip();
        	return bb;
        }
        
        private ByteBuffer encodeUserList(String userList) {
        	var encodedUserList = UTF8.encode(userList);
            ByteBuffer bb = ByteBuffer.allocate(1 + Integer.BYTES + encodedUserList.remaining());
            bb.put(OPCODE.CONNECTED_USERS_LIST.getCode()); // ou un autre opcode de réponse
            bb.putInt(encodedUserList.remaining());
            bb.put(encodedUserList);
            bb.flip();
            return bb;
        }
        
        private void sendLoginStatus(boolean accepted) {
            ByteBuffer bb = ByteBuffer.allocate(1);
            bb.put(accepted ? OPCODE.LOGIN_ACCEPTED.getCode() : OPCODE.LOGIN_REFUSED.getCode());
            bb.flip();
            queueMessage(bb);
        }
        
        private void logout() {
            if (peusdo != null) {
                loggedUsers.remove(peusdo);
                logger.info("Déconnexion de " + peusdo);
                broadcastMessage("Server", peusdo + " s'est déconnecté.");
            }
        }
        
        public void onMDPResponse(boolean success, long id) {
            // À adapter selon ce que tu veux faire après vérification
            if (opcode == OPCODE.LOGINAUTH.getCode()) {
                if (success) {
                    handleLogin(); // valide
                } else {
                    sendLoginStatus(false);
                }
            } else if (opcode == OPCODE.LOGIN.getCode()) {
                if (success) {
                    sendLoginStatus(false); // login déjà enregistré, refuse
                } else {
                    handleLogin(); // accepte login
                }
            }
        }

        private void processIn() {
        	for (;;) {
                switch (state) {
                    case WAITING_OPCODE -> {
                        var status = opcodeReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            opcode = opcodeReader.get();
                            logger.info("Opcode reçu: " + opcode);
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
                            logger.info("Id reçu: " + token);
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
                            logger.info("IP reçue: " + clientIp.address().getHostAddress() + ":" + clientIp.port());
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
                            logger.info("Peusdo reçu: " + peusdo);
                            if (opcode == OPCODE.MESSAGE.getCode()) {
                                state = State.WAITING_MESSAGE;
                            } 
                            else if (opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.OK_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
                            	state = State.WAITING_TARGET_PEUSDO;
                            }
                           else {
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
                            logger.info("target Peusdo reçu: " + peusdo);
                            if(opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
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
                            logger.info("Message reçu: " + message);
                            state = State.DONE;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case DONE -> {
                    	logger.info("Opcode: " + opcode);
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
        
        private ByteBuffer encodeBroadcastMessage(String sender, String message) {
            var encodedMessage = UTF8.encode(message);
            var encodedSender = UTF8.encode(sender);
            var bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + encodedSender.remaining() + Integer.BYTES + encodedMessage.remaining());
            bb.put(OPCODE.MESSAGE.getCode());
            bb.putInt(encodedSender.remaining());
            bb.put(encodedSender);
            bb.putInt(encodedMessage.remaining());
            bb.put(encodedMessage);
            bb.flip();
            return bb;
        }
        private static String dumpBuffer(ByteBuffer buffer) {
            StringBuilder sb = new StringBuilder();
            int pos = buffer.position();
            int lim = buffer.limit();
            for (int i = pos; i < lim; i++) {
                byte b = buffer.get(i);
                sb.append(String.format("%02X ", b));
            }
            sb.append(" | ");
            for (int i = pos; i < lim; i++) {
                byte b = buffer.get(i);
                if (b >= 32 && b < 127) {
                    sb.append((char) b);
                } else {
                    sb.append('.');
                }
            }
            return sb.toString();
        }
        
        private static String dumpBufferNum(ByteBuffer buffer) {
            StringBuilder sb = new StringBuilder();
            int pos = buffer.position();
            int lim = buffer.limit();
            for (int i = pos; i < lim; i++) {
                byte b = buffer.get(i);
                sb.append(String.format("%02X ", b));
            }
            sb.append(" | ");
            for (int i = pos; i < lim; i++) {
            	byte b = buffer.get(i);
                sb.append((char) b);
            }
            return sb.toString();
        }
        
        public void broadcastMessage(String sender, String message) {
            ByteBuffer bb = encodeBroadcastMessage(sender, message);
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