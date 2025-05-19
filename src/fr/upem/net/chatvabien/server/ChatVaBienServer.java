package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.GetUsersRequest;
import fr.upem.net.chatvabien.protocol.IntReader;
import fr.upem.net.chatvabien.protocol.IpReader;
import fr.upem.net.chatvabien.protocol.LoginRequest;
import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.MessageRequest;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.Request;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.User;

public class ChatVaBienServer {
	private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
	private static final Charset UTF8 = Charset.forName("UTF8");
	
	//private final Map<Long, SocketChannel> loggedUsers = new ConcurrentHashMap<>();
	private final Map<Long, User> loggedUsers = new ConcurrentHashMap<>();
	
    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    
    
    public ChatVaBienServer(int port) throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.bind(new InetSocketAddress(port));
        this.serverSocketChannel.configureBlocking(false);
        this.selector = Selector.open();
        this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void launch() throws IOException {
        while (!Thread.interrupted()) {
            selector.select();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                try {
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
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    public class Context {
        private final SocketChannel sc;
        private final SelectionKey key;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(1024);
        private final Queue<ByteBuffer> queueOut = new ArrayDeque<>();
        private final ByteReader opcodeReader = new ByteReader();
        private final LongReader idReader = new LongReader();
        private final StringReader stringReader = new StringReader();
        private final IpReader ipReader = new IpReader();
        private final IntReader intReader = new IntReader();

        private enum State {WAITING_TOTAL_SIZE, WAITING_OPCODE, WAITING_ID, WAITING_IP, WAITING_PEUSDO, WAITING_MESSAGE, DONE, ERROR}
        private State state = State.WAITING_TOTAL_SIZE;
        private byte opcode;
        private long id;
        private int totalSize;
        private String message;
        private String peusdo;

        private boolean closed = false;

        Context(SelectionKey key, Map<Long, User> loggedUsers) {
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
                    // Pas tout envoyé, on attend le prochain tour
                    break;
                }
                queueOut.remove(); // Message complètement envoyé
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
        
        private void handleMessage(byte opcode, long id) {
        	OPCODE op = OPCODE.fromCode(opcode);
            if (op == null) {
                System.err.println("Unknown opcode: " + opcode);
                return;
            }
            switch (op) {
            
            case LOGINAUTH -> {
            	boolean exists = loggedUsers.containsKey(id);
                logger.info("Login check for " + id + ": " + (exists ? "OK" : "Not logged in"));
                sendLoginStatus(true);
            }
            case LOGIN_ACCEPTED -> {
            	logger.info("Login accepted for " + id);
            }
            case LOGIN_REFUSED -> {
            	logger.info("Login refused for " + id);
            }
            case MESSAGE -> {
            	logger.info("Message received for " + id);
            }
            case REQUEST_PRIVATE -> {
            	logger.info("Private message request for " + id);
            }
            case OK_PRIVATE -> {
            	logger.info("Private message accepted for " + id);
            }
            case KO_PRIVATE -> {
            	logger.info("Private message failed for " + id);
            }
            case OPEN -> {
            	logger.info("Open request for " + id);
            }
            case FILE -> {
            	logger.info("File request for " + id);
            }
            case NOPE -> {
            	logger.info("Nope response for " + id);
            }
            default -> logger.severe("Unhandled opcode: " + op);
        }
    }
        
        public void handleLogin(long id) {
            if (loggedUsers.containsKey(id)) {
                sendLoginStatus(false);
            } else {
                loggedUsers.put(id, new User(peusdo, sc, false));
                sendLoginStatus(true);
            }
        }
        
        public void handleGetUsers() {
        	StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, User> entry : loggedUsers.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue().pseudo()).append("\n");
            }
            ByteBuffer response = encodeUserList(sb.toString());
            queueMessage(response);
        }
        
        private ByteBuffer encodeUserList(String userList) {
        	var encodedUserList = UTF8.encode(userList);
            ByteBuffer bb = ByteBuffer.allocate(1 + Integer.BYTES + encodedUserList.remaining());
            bb.put(OPCODE.GET_CONNECTED_USERS.getCode()); // ou un autre opcode de réponse
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

        private void processIn() {
        	for (;;) {
        		System.out.println(ipReader.state + " " + state);
                switch (state) {
	                case WAITING_TOTAL_SIZE -> {
	                	logger.info("\n\nBufferDEBUT : " + dumpBuffer(bufferIn));
	                    var status = intReader.process(bufferIn);
	                    if (status == ProcessStatus.DONE) {
	                        totalSize = intReader.get();
	                        logger.info("Total size reçu: " + totalSize);
	                        intReader.reset();
	                        state = State.WAITING_OPCODE;
	                    } else if (status == ProcessStatus.REFILL) {
	                        return;
	                    } else {
	                        closed = true;
	                        return;
	                    }
	                }
                    case WAITING_OPCODE -> {
                        var status = opcodeReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            opcode = opcodeReader.get();
                            logger.info("Opcode reçu: " + opcode);
                            opcodeReader.reset();
                            state = State.WAITING_ID;
                            //logger.info(state);
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
                            id = idReader.get();
                            logger.info("Id reçu: " + id);
                            idReader.reset();
                            state = State.WAITING_IP;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    
                    case WAITING_IP -> {
                    	logger.info("Buffer contenu avant IP read: " + dumpBuffer(bufferIn));
                        var status = ipReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            InetAddress ip = ipReader.get();
                            ipReader.reset();
                            logger.info("IP reçue: " + ip.getHostAddress());
                            state = State.WAITING_PEUSDO;
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
                            message = stringReader.get();
                            stringReader.reset();
                            logger.info("Peusdo reçu: " + message);
                            if (opcode == OPCODE.MESSAGE.getCode()) {
                                state = State.WAITING_MESSAGE;
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
                    	logger.info("Opcode: " + opcode + ", ID: " + id);
	                    Request request = switch (OPCODE.fromCode(opcode)) {
	                        case LOGIN -> new LoginRequest(id);
	                        case MESSAGE -> new MessageRequest(id, message);
	                        case GET_CONNECTED_USERS -> new GetUsersRequest();
	                        default -> null;
	                    };
	
	                    if (request != null) {
	                        request.handle(this); // traitement délégué à la requête
	                    } else {
	                        logger.warning("Unknown or unhandled opcode");
	                    }
	
	                    state = State.WAITING_TOTAL_SIZE;
                    }
                    case ERROR -> {
                        closed = true;
                        return;
                    }
				default -> throw new IllegalArgumentException("Unexpected value: " + state);
                }
            }
        }
        
        private ByteBuffer encodeBroadcastMessage(long senderId, String message) {
            var bytes = message.getBytes(StandardCharsets.UTF_8);
            var bb = ByteBuffer.allocate(1 + Long.BYTES + Integer.BYTES + bytes.length);
            bb.put(OPCODE.MESSAGE.getCode());
            bb.putLong(senderId);
            bb.putInt(bytes.length);
            bb.put(bytes);
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
        
        public void broadcastMessage(long senderId, String message) {
            ByteBuffer bb = encodeBroadcastMessage(senderId, message);
            for (Map.Entry<Long, User> entry : loggedUsers.entrySet()) {
                //long userId = entry.getKey();
                SocketChannel userChannel = entry.getValue().sc();
                if (userChannel.isOpen()) {  // ← SUPPRIME `userId != senderId`
                    SelectionKey userKey = userChannel.keyFor(selector);
                    if (userKey != null) {
                        Context userContext = (Context) userKey.attachment();
                        userContext.queueMessage(bb.duplicate());
                    }
                }
            }
        }
    }
    

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java ChatVaBienServer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        logger.log(Level.INFO, "Server launched");
        new ChatVaBienServer(port).launch();
    }
}