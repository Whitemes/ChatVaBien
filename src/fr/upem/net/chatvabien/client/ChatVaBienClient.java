package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.ByteReader;
import fr.upem.net.chatvabien.protocol.GetUsersRequest;
import fr.upem.net.chatvabien.protocol.Ip;
import fr.upem.net.chatvabien.protocol.IpReader;
import fr.upem.net.chatvabien.protocol.KOPrivateResquest;
import fr.upem.net.chatvabien.protocol.LoginRequest;
import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.Message;
import fr.upem.net.chatvabien.protocol.MessageRequest;
import fr.upem.net.chatvabien.protocol.OKPrivateRequest;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.Request;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.Trame;
import fr.upem.net.chatvabien.protocol.UserPrivate;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;
import fr.upem.net.chatvabien.protocol.MessageReader;

public class ChatVaBienClient {

	static private class Context implements ChannelContext {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Charset UTF8 = StandardCharsets.UTF_8;
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private final ArrayDeque<String> privateRequests = new ArrayDeque<>();
        private final BlockingQueue<String> pendingPrivateRequests = new ArrayBlockingQueue<>(100);
        private final Map<String, String> pendingRequestsDetails = new ConcurrentHashMap<>();
        private final Map<String, PrivateConnectionContext> privateConnections = new ConcurrentHashMap<>();
        private final Selector selector;
        private final Map<SelectionKey, ChannelContext> contexts;
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
        
        private Context(SelectionKey key, String login, Selector selector, Map<SelectionKey, ChannelContext> contexts) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.login = login;
            this.selector = selector;
            this.contexts = contexts;
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
                            logger.info("Opcode reçu: " + opcode);
                            
                            if (opcode == OPCODE.MESSAGE.getCode()) {
                                state = State.WAITING_MESSAGE;
                            } else if(opcode == OPCODE.REQUEST_PRIVATE.getCode()) {
                                state = State.WAITING_PEUSDO;
                            } else if(opcode == OPCODE.OK_PRIVATE.getCode()) {
                                state = State.WAITING_PEUSDO;
                            } else {
                                logger.info("Opcode " + opcode + " ne nécessite pas de lecture de message");
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
                            Message msg = messageReader.get();
                            System.out.println("Message reçu : " + msg);
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
                            System.out.println("peusdo reçu : " + peusdo);
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
                            System.out.println("tpeusdo reçu : " + target_peusdo);
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
                            System.out.println("token reçu : " + token);
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
                        byte[] addressBytes = new byte[addressSize];
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
                        logger.info("Traitement de l'opcode " + opcode);
                        if(opcode == OPCODE.LOGIN_ACCEPTED.getCode()) {
                            System.out.println("✅ Connexion acceptée par le serveur !");
                        } else if(opcode == OPCODE.LOGIN_REFUSED.getCode()) {
                            System.out.println("❌ Connexion refusée par le serveur.");
                            closed = true;
                            return;
                        } else if(opcode == OPCODE.MESSAGE.getCode()) {
                            logger.info("Message public traité");
                        } else if(opcode == OPCODE.REQUEST_PRIVATE.getCode()) {
                            logger.info("Request traité");
                            processRequestIn();
                        } else if(opcode == OPCODE.OK_PRIVATE.getCode()){
                        	logger.info("OK PRIVATE");
                        	processOKPrivateIn();
                        } else {
                            logger.warning("Opcode non géré: " + opcode);
                        }
                        state = State.WAITING_OPCODE;
                    }

                    case ERROR -> {
                        logger.severe("Erreur dans le traitement des messages");
                        closed = true;
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
                logger.info("Bytes non traités conservés, buffer en write-mode");
            } else {
                bufferIn.clear();
                logger.info("Tout traité, buffer remis en write-mode");
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
            privateRequests.offer(pseudo);
            processRequestOut();
            updateInterestOps();
        }
        
        private void processRequestOut() {
            while(!privateRequests.isEmpty()) {
                String targetPseudo = privateRequests.peek();
                PrivateRequest req = new PrivateRequest(login, targetPseudo);
                
                Trame trame = new Trame(OPCODE.REQUEST_PRIVATE.getCode(), login, req);
                ByteBuffer bb = trame.toByteBuffer();
                if(bufferOut.remaining() >= bb.remaining()) {
                    bufferOut.put(bb);
                    privateRequests.poll();
                } else {
                    break;
                }
            }
        }
        
        private void processRequestIn() {
            logger.info("=== DEBUT processRequestIn ===");
            logger.info("Expéditeur (peusdo): " + peusdo);
            logger.info("Destinataire (target_peusdo): " + target_peusdo);
            logger.info("Mon login: " + login);
            
            if (!target_peusdo.equals(login)) {
                logger.warning("Cette demande n'est pas pour moi ! target=" + target_peusdo + ", login=" + login);
                return;
            }
            
            pendingRequestsDetails.put(peusdo, peusdo);
            
            try {
                pendingPrivateRequests.put(peusdo);
                System.out.println("📨 Demande de connexion privée reçue de " + peusdo);
                System.out.println("Tapez 'accept " + peusdo + "' ou 'refuse " + peusdo + "'");
            } catch (InterruptedException e) {
                logger.warning("Erreur lors de l'ajout de la demande privée en attente");
            }
            logger.info("=== FIN processRequestIn ===");
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
        	System.out.println("DEBUG: Rayan envoie OK_PRIVATE: login=" + login + ", targetPseudo=" + targetPseudo);
            Random random = new Random();
            long token = random.nextLong();
            InetSocketAddress localAddress = (InetSocketAddress) sc.getLocalAddress();
            InetAddress inetAddress = localAddress.getAddress();
            int port = localAddress.getPort();
            
            System.out.println("=== RAYAN ENVOIE ===");
            System.out.println("IP: " + inetAddress.getHostAddress());
            System.out.println("Port: " + port);
            System.out.println("Token: " + token);
            
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
            System.out.println("🎉 OK_PRIVATE reçu !");
            System.out.println("Pseudo: " + peusdo);
            System.out.println("IP: " + clientIp.address().getHostAddress());
            System.out.println("Port: " + clientIp.port());
            System.out.println("Token: " + token);
            
            try {
                InetSocketAddress targetAddress = new InetSocketAddress(clientIp.address(), clientIp.port());
                SocketChannel privateChannel = SocketChannel.open();
                privateChannel.configureBlocking(false);
                
                SelectionKey privateKey = privateChannel.register(selector, SelectionKey.OP_CONNECT);
                PrivateConnectionContext privateContext = new PrivateConnectionContext(privateKey, peusdo, token);
                contexts.put(privateKey, privateContext);
                
                privateConnections.put(peusdo, privateContext);
                privateChannel.connect(targetAddress);
                
                System.out.println("🔄 Tentative de connexion privée vers " + peusdo + "...");
                
            } catch (IOException e) {
                System.err.println("❌ Erreur lors de l'établissement de la connexion privée: " + e.getMessage());
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
        	logger.info("Bytes lus: " + bytesRead);
        	
            if (bytesRead == -1) {
                logger.info("Connexion fermée par le serveur");
                closed = true;
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
            logger.info("Bytes écrits: " + bytesWritten);
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
            
            logger.info("✅ Connecté au serveur");
            updateInterestOps();
        }
        
        private void sendLogin() {
        	if (loginSent) return;
        	
            Trame trame = new Trame(OPCODE.LOGIN.getCode(), login, new LoginRequest());
            ByteBuffer bb = trame.toByteBuffer();
            
            if (bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                loginSent = true;
                logger.info("Login envoyé: " + login);
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
	    private boolean connected = false;
	    
	    private PrivateConnectionContext(SelectionKey key, String remotePseudo, long connectId) {
	        this.key = key;
	        this.sc = (SocketChannel) key.channel();
	        this.remotePseudo = remotePseudo;
	        this.connectId = connectId;
	    }
	    
	    private void sendOpen() {
	        if (openSent) return;
	        
	        ByteBuffer bb = ByteBuffer.allocate(9);
	        bb.put((byte) OPCODE.OPEN.getCode());
	        bb.putLong(connectId);
	        bb.flip();
	        
	        if (bufferOut.remaining() >= bb.remaining()) {
	            bufferOut.put(bb);
	            openSent = true;
	            connected = true;
	            logger.info("Message OPEN envoyé avec connect_id: " + connectId);
	        }
	    }
	    
	    private void queuePrivateMessage(String message) {
	        messageQueue.offer(message);
	        processOut();
	        updatePrivateInterestOps();
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
	        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
	        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 0 + 4 + msgBytes.length); // opcode + taille_login + login + taille_msg + msg
	        
	        bb.put((byte) OPCODE.MESSAGE.getCode());
	        bb.putInt(0);
	        bb.putInt(msgBytes.length);
	        bb.put(msgBytes);
	        bb.flip();
	        
	        return bb;
	    }
	    
	    private void processIn() {
	        bufferIn.flip();
	        
	        while (bufferIn.hasRemaining()) {
	            if (bufferIn.remaining() < 1) {
	                break;
	            }
	            
	            byte opcode = bufferIn.get();
	            
	            if (opcode == OPCODE.MESSAGE.getCode()) {
	                if (bufferIn.remaining() < 4) {
	                    bufferIn.position(bufferIn.position() - 1);
	                    break;
	                }
	                int loginSize = bufferIn.getInt();
	                
	                if (bufferIn.remaining() < loginSize) {
	                    bufferIn.position(bufferIn.position() - 5);
	                    break;
	                }
	                bufferIn.position(bufferIn.position() + loginSize);
	                
	                if (bufferIn.remaining() < 4) {
	                    bufferIn.position(bufferIn.position() - 5 - loginSize);
	                    break;
	                }
	                int msgSize = bufferIn.getInt();
	                
	                if (bufferIn.remaining() < msgSize) {
	                    bufferIn.position(bufferIn.position() - 9 - loginSize);
	                    break;
	                }
	                
	                byte[] msgBytes = new byte[msgSize];
	                bufferIn.get(msgBytes);
	                String message = new String(msgBytes, StandardCharsets.UTF_8);
	                
	                System.out.println("💬 [PRIVÉ] " + remotePseudo + ": " + message);
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
	        if (!openSent) {
	            sendOpen();
	        }
	        
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
	        logger.info("✅ Connexion privée établie avec " + remotePseudo);
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
	

    private static int BUFFER_SIZE = 10_000;
    private static Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());

    private final SocketChannel sc;
    private final Selector selector;
    private final InetSocketAddress serverAddress;
    private final String login;
    private final Thread console;
    private Context uniqueContext;
    
    private final Map<SelectionKey, ChannelContext> contexts = new ConcurrentHashMap<>();
    
    private final BlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);
    
    private final Map<Long, PrivateConnectionContext> pendingConnections = new ConcurrentHashMap<>();

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
            System.out.println("✅ Connexion privée acceptée avec " + pseudo);
            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.bind(new InetSocketAddress(0));

                int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();

                SelectionKey sscKey = ssc.register(selector, SelectionKey.OP_ACCEPT);

                //contexts.put(sscKey, pseudo);

                sendCommand("INTERNAL_ACCEPT_PRIVATE:" + pseudo);

                System.out.println("✅ Prêt à accepter la connexion privée pour " + pseudo + " sur port " + port);
                
                ///C CETTE IP QUE LE TARGET ENVOI OK PRIVATE

            } catch (IOException e) {
                System.err.println("❌ Erreur lors de la création du ServerSocketChannel pour la connexion privée : " + e.getMessage());
            }

        } else if ("refuse".equals(action)) {
            sendCommand("INTERNAL_REFUSE_PRIVATE:" + pseudo);
            System.out.println("❌ Connexion privée refusée avec " + pseudo);
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
            if (command.startsWith("INTERNAL_ACCEPT_PRIVATE:")) {
                String senderPseudo = command.substring("INTERNAL_ACCEPT_PRIVATE:".length());
                uniqueContext.sendOKPrivate(senderPseudo);
            } else if (command.startsWith("INTERNAL_REFUSE_PRIVATE:")) {
                String senderPseudo = command.substring("INTERNAL_REFUSE_PRIVATE:".length());
                uniqueContext.sendKOPrivate(senderPseudo);
            } else if (command.startsWith("@")) {
                String[] parts = command.substring(1).split(" ", 2);
                if (parts.length == 2) {
                    String pseudo = parts[0];
                    String message = parts[1];
                    
                    PrivateConnectionContext privateContext = uniqueContext.privateConnections.get(pseudo);
                    if (privateContext != null && privateContext.connected) {
                        privateContext.queuePrivateMessage(message);
                        System.out.println("(Privé) -> " + pseudo + " : " + message);
                    } else {
                        logger.info("Demande de connexion privée pour: " + pseudo);
                        uniqueContext.queuePrivateRequest(pseudo);
                    }
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
        uniqueContext = new Context(key, login, selector, contexts);
        contexts.put(key, uniqueContext);
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
            ChannelContext context = contexts.get(key);
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
            throw new UncheckedIOException(ioe);
        }
    }


    private void silentlyClose(SelectionKey key) {
        contexts.remove(key);
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