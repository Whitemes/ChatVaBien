package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.Ip;
import fr.upem.net.chatvabien.protocol.IpReader;
import fr.upem.net.chatvabien.protocol.KOPrivateResquest;
import fr.upem.net.chatvabien.protocol.LoginRequest;
import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.Message;
import fr.upem.net.chatvabien.protocol.OKPrivateRequest;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.Trame;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.MessageReader;

public class ChatVaBienClient {

	static private class Context implements ChannelContext {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private final BlockingQueue<String> pendingPrivateRequests = new ArrayBlockingQueue<>(100);
        private final Map<String, String> pendingRequestsDetails = new ConcurrentHashMap<>();
        private PrivateConnection privateConnection = null;
        private final Selector selector;
        private boolean closed = false;
        private enum State {WAITING_OPCODE, WAITING_PEUSDO, WAITING_MESSAGE, WAITING_TARGET_PEUSDO, WAITING_TOKEN, WAITING_IP, DONE, ERROR};
        private State state = State.WAITING_OPCODE;
        private byte opcode;
        private final String login;
        private boolean loginSent = false;
        private String peusdo;
        private String target_peusdo;
        private Ip clientIp;
        private long token;

        private final MessageReader messageReader = new MessageReader();
        private final ByteReader byteReader = new ByteReader();
        private final StringReader stringReader = new StringReader();
        private final IpReader ipReader = new IpReader();
        private final LongReader longReader = new LongReader();
        
        private Context(SelectionKey key, String login, Selector selector) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.login = login;
            this.selector = selector;
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

        /**
         * Process the content of bufferIn
         *
         * The convention is that bufferIn is in write-mode before the call to process
         * and after the call
         *
         */
        private void processIn() {
            bufferIn.flip();
            Message msg = null;
            
            while (true) {
                switch (state) {
                    case WAITING_OPCODE -> {
                        if (!bufferIn.hasRemaining()) {
                            break;
                        }
                        var status = byteReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            opcode = byteReader.get();
                            byteReader.reset();
                            
                            if (opcode == OPCODE.MESSAGE.getCode()) {
                                state = State.WAITING_MESSAGE;
                            } else if(opcode == OPCODE.REQUEST_PRIVATE.getCode()) {
                                state = State.WAITING_PEUSDO;
                            } else if(opcode == OPCODE.OK_PRIVATE.getCode()) {
                                state = State.WAITING_PEUSDO;
                            } else {
                                state = State.DONE;
                            }
                        } else if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return;
                        } else {
                            logger.severe("Erreur lors de la lecture de l'opcode");
                            state = State.ERROR;
                        }
                    }

                    case WAITING_MESSAGE -> {
                        if (!bufferIn.hasRemaining()) {
                            break;
                        }
                        var status = messageReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            msg = messageReader.get();
                            messageReader.reset();
                            state = State.DONE;
                        } else if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return;
                        } else {
                            logger.severe("Erreur lors de la lecture du message");
                            closed = true;
                            return;
                        }
                    }

                    case WAITING_PEUSDO -> {
                        if (!bufferIn.hasRemaining()) {
                            break;
                        }
                        var status = stringReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            peusdo = stringReader.get();
                            stringReader.reset();
                            state = State.WAITING_TARGET_PEUSDO;
                        } else if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return;
                        } else {
                            logger.severe("Erreur lors de la lecture du peusdo");
                            closed = true;
                            return;
                        }
                    }

                    case WAITING_TARGET_PEUSDO -> {
                        if (!bufferIn.hasRemaining()) {
                            break;
                        }
                        var status = stringReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            target_peusdo = stringReader.get();
                            stringReader.reset();
                            if(opcode == OPCODE.OK_PRIVATE.getCode()) {
                            	state = State.WAITING_IP;
                            } else {
                            	state = State.DONE;
                            }
                        } else if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return;
                        } else {
                            logger.severe("Erreur lors de la lecture du target_peusdo");
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_TOKEN -> {
                        var status = longReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            token = longReader.get();
                            longReader.reset();
                            state = State.DONE;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
                            closed = true;
                            return;
                        }
                    }
                    case WAITING_IP -> {
                        if (bufferIn.remaining() < 1) {
                            break; 
                        }
                        byte ipType = bufferIn.get();
                        
                        int addressSize;
                        if (ipType == 0x04) {
                            addressSize = 4;
                        } else if (ipType == 0x06) {
                            addressSize = 16;
                        } else {
                            System.out.println("Type IP invalide: " + ipType);
                            closed = true;
                            return;
                        }
                        
                        if (bufferIn.remaining() < addressSize) {
                            bufferIn.position(bufferIn.position() - 1);
                            break;
                        }
                        byte[] addressBytes = new byte[addressSize]; //byte pour IP
                        bufferIn.get(addressBytes);
                        
                        if (bufferIn.remaining() < 4) {
                            bufferIn.position(bufferIn.position() - addressSize - 1);
                            break;
                        }
                        int port = bufferIn.getInt();
                        
                        try {
                            InetAddress address = InetAddress.getByAddress(addressBytes);
                            clientIp = new Ip(ipType, address, port);
                            System.out.println("clientip reçu : " + clientIp);
                            state = State.WAITING_TOKEN;
                        } catch (Exception e) {
                            System.out.println("Erreur création IP: " + e.getMessage());
                            closed = true;
                            return;
                        }
                    }

                    case DONE -> {
                        if(opcode == OPCODE.LOGIN_ACCEPTED.getCode()) {
                            System.out.println("Connexion acceptée par le serveur !");
                        } else if(opcode == OPCODE.LOGIN_REFUSED.getCode()) {
                            System.out.println("Connexion refusée par le serveur.");
                            closed = true;
                            silentlyClose();
                            return;
                        } else if(opcode == OPCODE.MESSAGE.getCode()) {
                        	System.out.println("-> " + msg.peusdo() + ": " + msg.text());
                        } else if(opcode == OPCODE.REQUEST_PRIVATE.getCode()) {
                            processRequestIn();
                        } else if(opcode == OPCODE.OK_PRIVATE.getCode()){
                        	processOKPrivateIn();
                        } else {
                            logger.warning("Opcode non géré: " + opcode);
                        }
                        state = State.WAITING_OPCODE;
                    }

                    case ERROR -> {
                        logger.severe("Erreur dans le traitement des messages");
                        closed = true;
                        silentlyClose();
                        return;
                    }

                    default -> throw new IllegalStateException("État inconnu : " + state);
                }
                
                if (!bufferIn.hasRemaining() && state != State.DONE) {
                    break;
                }
            }
            
            if (bufferIn.hasRemaining()) {
                bufferIn.compact();
            } else {
                bufferIn.clear();
            }
        }

        /**
         * Add a message to the message queue, tries to fill bufferOut and updateInterestOps
         *
         * @param bb
         */
        private void queueMessage(Message msg) {
        	queue.offer(msg);
            processOut();
            updateInterestOps();
        }
        
        private void queuePrivateRequest(String pseudo) {
        	pendingPrivateRequests.offer(pseudo);
            processRequestOut();
            updateInterestOps();
        }
        
        private void processRequestOut() {
            while(!pendingPrivateRequests.isEmpty()) {
                String targetPseudo = pendingPrivateRequests.peek();
                PrivateRequest req = new PrivateRequest(login, targetPseudo);
                
                Trame trame = new Trame(OPCODE.REQUEST_PRIVATE.getCode(), login, req);
                ByteBuffer bb = trame.toByteBuffer();
                if(bufferOut.remaining() >= bb.remaining()) {
                    bufferOut.put(bb);
                    pendingPrivateRequests.poll();
                } else {
                    break;
                }
            }
        }
        
        private void processRequestIn() {
            if (!target_peusdo.equals(login)) {
                logger.warning("Faux destinataire ! target=" + target_peusdo + ", login=" + login);
                return;
            }
            
            pendingRequestsDetails.put(peusdo, peusdo);
            
            try {
                pendingPrivateRequests.put(peusdo);
                System.out.println("Demande de connexion privée reçue de " + peusdo);
                System.out.println("Tapez 'accept " + peusdo + "' ou 'refuse " + peusdo + "'");
            } catch (InterruptedException e) {
                logger.warning("Erreur lors de l'ajout de la demande privée en attente");
            }
        }
        
        

        /**
         * Try to fill bufferOut from the message queue
         *
         */
        private void processOut() {
        	while (bufferOut.hasRemaining() && !queue.isEmpty()) {
                Message req = queue.peek();
                Trame trame = new Trame(OPCODE.MESSAGE.getCode(), req.peusdo(), req);
                ByteBuffer bb = trame.toByteBuffer();
                if (bufferOut.remaining() >= bb.remaining()) {
                    bufferOut.put(bb);
                    queue.poll();
                } else {
                    break;
                }
            }
        }
        
        private void sendOKPrivate(String targetPseudo) throws IOException {
            Random random = new Random();
            long token = random.nextLong();
            InetSocketAddress localAddress = (InetSocketAddress) sc.getLocalAddress();
            InetAddress inetAddress = localAddress.getAddress();
            int port = localAddress.getPort();
            

            
            OKPrivateRequest req = new OKPrivateRequest(login, targetPseudo, new Ip((byte) 0x4, inetAddress, port), token);
            Trame trame = new Trame(OPCODE.OK_PRIVATE.getCode(), login, req);
            ByteBuffer bb = trame.toByteBuffer();
            if(bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                updateInterestOps();
            }
        }

        private void sendKOPrivate(String targetPseudo) {
            KOPrivateResquest req = new KOPrivateResquest(login, targetPseudo);
            Trame trame = new Trame(OPCODE.KO_PRIVATE.getCode(), login, req);
            ByteBuffer bb = trame.toByteBuffer();
            if(bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                updateInterestOps();
            }
        }
        
        private void processOKPrivateIn() {
            
            try {
                InetSocketAddress targetAddress = new InetSocketAddress(clientIp.address(), clientIp.port());
                SocketChannel privateChannel = SocketChannel.open();
                privateChannel.configureBlocking(false);
                
                SelectionKey privateKey = privateChannel.register(selector, SelectionKey.OP_CONNECT);
                PrivateConnectionContext privateContext = new PrivateConnectionContext(privateKey, peusdo, token);
                
                privateChannel.connect(targetAddress);
                
                System.out.println("Tentative de connexion privée vers " + peusdo + "...");
                
            } catch (IOException e) {
                System.err.println("Erreur lors de l'établissement de la connexion privée: " + e.getMessage());
            }
        }
        

        /**
         * Update the interestOps of the key looking only at values of the boolean
         * closed and of both ByteBuffers.
         *
         * The convention is that both buffers are in write-mode before the call to
         * updateInterestOps and after the call. Also it is assumed that process has
         * been be called just before updateInterestOps.
         */
        private void updateInterestOps() {
        	int ops = 0;
        	
        	if (!closed) {
                ops |= SelectionKey.OP_READ;
            }
        	
        	if (!loginSent) {
        		sendLogin();
        	}
        	
        	if (bufferOut.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }

            key.interestOps(ops);
        }

        private void silentlyClose() {
            try {
                sc.close();
            } catch (IOException e) {
                // ignore exception
            }
        }

        /**
         * Performs the read action on sc
         *
         * The convention is that both buffers are in write-mode before the call to
         * doRead and after the call
         *
         * @throws IOException
         */
        @Override
		public void doRead() throws IOException {
        	int bytesRead = sc.read(bufferIn);
        	
            if (bytesRead == -1) {
                logger.info("Connexion fermée par le serveur");
                closed = true;
                silentlyClose();
                return;
            }
            if (bytesRead > 0) {
                processIn();
            }
            updateInterestOps();
        }

        /**
         * Performs the write action on sc
         *
         * The convention is that both buffers are in write-mode before the call to
         * doWrite and after the call
         *
         * @throws IOException
         */
        @Override
		public void doWrite() throws IOException {
        	bufferOut.flip();
            int bytesWritten = sc.write(bufferOut);
            bufferOut.compact();
            
            if (bytesWritten > 0) {
                processOut();
                processRequestOut();
            }
            updateInterestOps();
        }
        
        @Override
        public void doConnect() throws IOException {
        	if (!sc.finishConnect()) {
                return;
            }
            
            logger.info("Connecté au serveur");
            updateInterestOps();
        }
        
        private void sendLogin() {
        	if (loginSent) return;
        	
            Trame trame = new Trame(OPCODE.LOGIN.getCode(), login, new LoginRequest());
            ByteBuffer bb = trame.toByteBuffer();
            
            if (bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                loginSent = true;
            }
        }
    }
	
	static private class PrivateConnectionContext implements ChannelContext {
	    private final SelectionKey key;
	    private final SocketChannel sc;
	    private final String remotePseudo;
	    private final long connectId;
	    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
	    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
	    private final ArrayDeque<String> messageQueue = new ArrayDeque<>();
	    private boolean openSent = false;
	    private enum State {WAITING_OPCODE, WAITING_MESSAGE, DONE, ERROR};
        private State state = State.WAITING_OPCODE;
        private byte opcode;
        
        private final MessageReader messageReader = new MessageReader();
        private final ByteReader byteReader = new ByteReader();
	    
	    private PrivateConnectionContext(SelectionKey key, String remotePseudo, long connectId) {
	        this.key = key;
	        this.sc = (SocketChannel) key.channel();
	        this.remotePseudo = remotePseudo;
	        this.connectId = connectId;
	    }
	    
	    private void processOut() {
	        while (bufferOut.hasRemaining() && !messageQueue.isEmpty()) {
	            String msg = messageQueue.peek();
	            
	            ByteBuffer bb = createPrivateMessage(msg);
	            
	            if (bufferOut.remaining() >= bb.remaining()) {
	                bufferOut.put(bb);
	                messageQueue.poll();
	            } else {
	                break;
	            }
	        }
	    }
	    
	    private ByteBuffer createPrivateMessage(String message) {
	        var msgBytes = UTF8.encode(message);
	        ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + 0 + Integer.BYTES + msgBytes.remaining());
	        
	        bb.put((byte) OPCODE.MESSAGE.getCode());
	        bb.putInt(0); //ignore peusdo
	        bb.putInt(msgBytes.remaining());
	        bb.put(msgBytes);
	        bb.flip();
	        
	        return bb;
	    }
	    
	    private void processIn() {
	        bufferIn.flip();
	        Message msg = null;
	        
	        while (true) {
	        	switch (state) {
		            case WAITING_OPCODE -> {
		                if (!bufferIn.hasRemaining()) {
		                    break;
		                }
		                var status = byteReader.process(bufferIn);
		                if (status == ProcessStatus.DONE) {
		                    opcode = byteReader.get();
		                    byteReader.reset();
		                    
		                    if (opcode == OPCODE.MESSAGE.getCode()) {
		                        state = State.WAITING_MESSAGE;
		                    } else {
		                        state = State.DONE;
		                    }
		                } else if (status == ProcessStatus.REFILL) {
		                    bufferIn.compact();
		                    return;
		                } else {
		                    logger.severe("Erreur lors de la lecture de l'opcode");
		                    state = State.ERROR;
		                }
		            }
		
		            case WAITING_MESSAGE -> {
		                if (!bufferIn.hasRemaining()) {
		                    break;
		                }
		                var status = messageReader.process(bufferIn);
		                if (status == ProcessStatus.DONE) {
		                    msg = messageReader.get();
		                    messageReader.reset();
		                    state = State.DONE;
		                } else if (status == ProcessStatus.REFILL) {
		                    bufferIn.compact();
		                    return;
		                } else {
		                    logger.severe("Erreur lors de la lecture du message");
		                    return;
		                }
		            }
		            case DONE -> {
		                state = State.WAITING_OPCODE;
		                if(opcode == OPCODE.MESSAGE.getCode()) {
		                	System.out.println("(Privé) -> " + msg.peusdo() + ": " + msg.text());
		                }
		            }
		
		            case ERROR -> {
		                logger.severe("Erreur dans le traitement des messages");
		                silentlyClosePrivate();
		                return;
		            }
		
		            default -> throw new IllegalStateException("État inconnu : " + state);
		        }
		        
		        if (!bufferIn.hasRemaining() && state != State.DONE) {
		            break;
		        }
		    }
		    
		    if (bufferIn.hasRemaining()) {
		        bufferIn.compact();
		    } else {
		        bufferIn.clear();
		    }
	    
	    }
	    
	    private void updatePrivateInterestOps() {
	        int ops = SelectionKey.OP_READ;
	        
	        if (!openSent || bufferOut.position() > 0) {
	            ops |= SelectionKey.OP_WRITE;
	        }
	        
	        if (key.isValid()) {
	            key.interestOps(ops);
	        }
	    }
	    
	    @Override
		public void doRead() throws IOException {
	        int bytesRead = sc.read(bufferIn);
	        if (bytesRead == -1) {
	            logger.info("Connexion privée fermée par " + remotePseudo);
	            silentlyClosePrivate();
	            return;
	        }
	        if (bytesRead > 0) {
	            processIn();
	        }
	        updatePrivateInterestOps();
	    }
	    
	    @Override
		public void doWrite() throws IOException {
	        bufferOut.flip();
	        int bytesWritten = sc.write(bufferOut);
	        bufferOut.compact();
	        
	        if (bytesWritten > 0) {
	            processOut();
	        }
	        updatePrivateInterestOps();
	    }
	    
	    @Override
		public void doConnect() throws IOException {
	        if (!sc.finishConnect()) {
	            return;
	        }
	        logger.info("Connexion privée établie avec " + remotePseudo);
	        updatePrivateInterestOps();
	    }
	    
	    private void silentlyClosePrivate() {
	        try {
	            sc.close();
	        } catch (IOException e) {
	            // ignore
	        }
	    }
	}
	

    private static final int BUFFER_SIZE = 1024;
    private static final Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private final SocketChannel sc;
    private final Selector selector;
    private final InetSocketAddress serverAddress;
    private final String login;
    private final Thread console;
    private Context uniqueContext;
    
    
    private final BlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);
    
    public ChatVaBienClient(String login, InetSocketAddress serverAddress) throws IOException {
        this.serverAddress = serverAddress;
        this.login = login;
        this.sc = SocketChannel.open();
        this.selector = Selector.open();
        this.console = Thread.ofPlatform().unstarted(this::consoleRun);
    }

    private void consoleRun() {
        try {
            try (var scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    var input = scanner.nextLine();
                    
                    if (input.startsWith("accept ") || input.startsWith("refuse ")) {
                        handlePrivateRequestResponse(input);
                    } else {
                        sendCommand(input);
                    }
                }
            }
            logger.info("Console thread stopping");
        } catch (InterruptedException e) {
            logger.info("Console thread has been interrupted");
        }
    }

    /**
     * Send instructions to the selector via a BlockingQueue and wake it up
     *
     * @param msg
     * @throws InterruptedException
     */
    private void sendCommand(String msg) throws InterruptedException {
        commandQueue.put(msg);
        selector.wakeup();
    }
    
    private void handlePrivateRequestResponse(String input) throws InterruptedException {
        String[] parts = input.split(" ", 2);
        if (parts.length != 2) {
            System.out.println("Usage: accept/refuse <pseudo>");
            return;
        }
        
        String action = parts[0];
        String pseudo = parts[1];
        
        if (!uniqueContext.pendingRequestsDetails.containsKey(pseudo)) {
            System.out.println("Demande inconnue de: " + pseudo);
            return;
        }
        
        if ("accept".equals(action)) {
            System.out.println("Connexion privée acceptée avec " + pseudo);
            

        } else if ("refuse".equals(action)) {
            sendCommand("REFUSE_PRIVATE:" + pseudo);
            System.out.println("Connexion privée refusée avec " + pseudo);
        }
        
        uniqueContext.pendingRequestsDetails.remove(pseudo);
    }

    /**
     * Processes the command from the BlockingQueue 
     * @throws IOException 
     */
    private void processCommands() throws IOException {
        String command;
        while ((command = commandQueue.poll()) != null) {
        	
            if (command.startsWith("ACCEPT_PRIVATE:")) {
                String senderPseudo = command.substring("ACCEPT_PRIVATE:".length());
                uniqueContext.sendOKPrivate(senderPseudo);
                
            } else if (command.startsWith("REFUSE_PRIVATE:")) {
                String senderPseudo = command.substring("REFUSE_PRIVATE:".length());
                uniqueContext.sendKOPrivate(senderPseudo);
                
            } else if (command.startsWith("@")) {
                String[] parts = command.substring(1).split(" ", 2);
                
                if (parts.length == 2) {
                    String pseudo = parts[0];
                    
                    logger.info("Demande de connexion privée pour: " + pseudo);
                    uniqueContext.queuePrivateRequest(pseudo);
                    
                } else if (parts.length == 1) {
                    String pseudo = parts[0];
                    if (!pseudo.isEmpty()) {
                        logger.info("Demande de connexion privée pour: " + pseudo);
                        uniqueContext.queuePrivateRequest(pseudo);
                    }
                }
            } else if (command.startsWith("/")) {
                if (command.equals("/getusers")) {
                    logger.info("GetUsers pas encore implémenté");
                } else {
                    logger.info("Transfert de fichiers pas encore implémenté");
                }
            } else {
                Message trame = new Message(login, command);
                uniqueContext.queueMessage(trame);
            }
        }
    }

    public void launch() throws IOException {
        sc.configureBlocking(false);
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        uniqueContext = new Context(key, login, selector);
        sc.connect(serverAddress);

        console.start();

        while (!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                processCommands();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            ChannelContext context = null;
            
            if (key == uniqueContext.key) {
                context = uniqueContext;
            }
            
            if (context == null) {
                return;
            }
            
            if (key.isValid() && key.isConnectable()) {
                context.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                context.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                context.doRead();
            }
        } catch (IOException ioe) {
            silentlyClose(key);
            throw new UncheckedIOException(ioe);
        }
    }


    private void silentlyClose(SelectionKey key) {
        Channel sc = (Channel) key.channel();
        try {
            sc.close();
        } catch (IOException e) {
            // ignore exception
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 3) {
            usage();
            return;
        }
        new ChatVaBienClient(args[0], new InetSocketAddress(args[1], Integer.parseInt(args[2]))).launch();
    }

    private static void usage() {
        System.out.println("Usage : ClientChat login hostname port");
    }
}