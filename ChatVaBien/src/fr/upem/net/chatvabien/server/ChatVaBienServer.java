package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.IpReader;
import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.StringReader;

public class ChatVaBienServer {
	private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
	private static final Charset UTF8 = Charset.forName("UTF8");
	
	private final Map<Long, SocketChannel> loggedUsers = new ConcurrentHashMap<>();
	
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

    private class Context {
        private final SocketChannel sc;
        private final SelectionKey key;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(1024);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(1024);
        private final ByteReader opcodeReader = new ByteReader();
        private final LongReader idReader = new LongReader();
        private final StringReader stringReader = new StringReader();
        private final IpReader ipReader = new IpReader();

        private enum State {WAITING_OPCODE, WAITING_ID, WAITING_IP, WAITING_MESSAGE, DONE, ERROR}
        private State state = State.WAITING_OPCODE;
        private byte opcode;
        private long id;

        private boolean closed = false;

        Context(SelectionKey key, Map<Long, SocketChannel> loggedUsers) {
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
            bufferOut.flip();
            sc.write(bufferOut);
            bufferOut.compact();

            if (bufferOut.position() == 0) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        }

        void queueMessage(ByteBuffer bb) {
            if (bb.remaining() > bufferOut.remaining()) {
                System.err.println("Output buffer overflow, closing client.");
                closed = true;
                return;
            }
            bufferOut.put(bb);
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
            if (bufferOut.position() > 0) {
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
            case LOGIN -> {
                if (loggedUsers.containsKey(id)) {
                    System.out.println("User already logged in: " + id);
                } else {
                    loggedUsers.put(id, sc);
                    System.out.println("User logged in: " + id);
                }
            }
            case LOGINAUTH -> {
                boolean exists = loggedUsers.containsKey(id);
                System.out.println("Login check for " + id + ": " + (exists ? "OK" : "Not logged in"));
            }
            case LOGIN_ACCEPTED -> {
                System.out.println("Login accepted for " + id);
            }
            case LOGIN_REFUSED -> {
                System.out.println("Login refused for " + id);
            }
            case MESSAGE -> {
                System.out.println("Message received for " + id);
            }
            case REQUEST_PRIVATE -> {
                System.out.println("Private message request for " + id);
            }
            case OK_PRIVATE -> {
                System.out.println("Private message accepted for " + id);
            }
            case KO_PRIVATE -> {
                System.out.println("Private message failed for " + id);
            }
            case OPEN -> {
                System.out.println("Open request for " + id);
            }
            case FILE -> {
                System.out.println("File request for " + id);
            }
            case NOPE -> {
                System.out.println("Nope response for " + id);
            }
            default -> System.err.println("Unhandled opcode: " + op);
        }
    }

        private void processIn() {
        	for (;;) {
                switch (state) {
                    case WAITING_OPCODE -> {
                        var status = opcodeReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            opcode = opcodeReader.get();
                            System.out.println("Opcode reçu: " + opcode);
                            opcodeReader.reset();
                            state = State.WAITING_ID;
                            //System.out.println(state);
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
                            System.out.println("Id reçu: " + id);
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
                        var status = ipReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            InetAddress ip = ipReader.get();
                            ipReader.reset();
                            System.out.println("IP reçue: " + ip.getHostAddress());
                            state = State.DONE;
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
                            String message = stringReader.get();
                            stringReader.reset();
                            System.out.println("Message reçu: " + message);
                            state = State.WAITING_OPCODE;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case DONE -> {
                        System.out.println("Opcode: " + opcode + ", ID: " + id);
                        handleMessage(opcode, id);
                        state = State.WAITING_OPCODE;
                    }
                    case ERROR -> {
                        closed = true;
                        return;
                    }
                }
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