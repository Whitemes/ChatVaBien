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

	static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Charset UTF8 = StandardCharsets.UTF_8;
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private final ArrayDeque<String> privateRequests = new ArrayDeque<>();
        private final BlockingQueue<String> pendingPrivateRequests = new ArrayBlockingQueue<>(100);
        private final Map<String, String> pendingRequestsDetails = new ConcurrentHashMap<>();
        private final Map<String, UserPrivate> privateConnections = new ConcurrentHashMap<>();
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
        
        private Context(SelectionKey key, String login) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.login = login;
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
                        var status = ipReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            clientIp = ipReader.get();
                            ipReader.reset();
                            state = State.WAITING_TOKEN;
                        } else if (status == ProcessStatus.REFILL) {
                            return;
                        } else {
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
                        	//processOKPrivateIn();
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
            
            pendingRequestsDetails.put(peusdo, target_peusdo);
            
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
            Random random = new Random();
            long token = random.nextLong();
            InetSocketAddress localAddress = (InetSocketAddress) sc.getLocalAddress();
            InetAddress inetAddress = localAddress.getAddress();
            int port = localAddress.getPort();
            
            OKPrivateRequest req = new OKPrivateRequest(peusdo, targetPseudo, new Ip((byte) 0x4, inetAddress, port), token);
            Trame trame = new Trame(OPCODE.OK_PRIVATE.getCode(), peusdo, req);
            ByteBuffer bb = trame.toByteBuffer();
            if(bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                updateInterestOps();
            }
        }

        private void sendKOPrivate(String targetPseudo) {
            KOPrivateResquest req = new KOPrivateResquest(peusdo, targetPseudo);
            Trame trame = new Trame(OPCODE.KO_PRIVATE.getCode(), peusdo, req);
            ByteBuffer bb = trame.toByteBuffer();
            if(bufferOut.remaining() >= bb.remaining()) {
                bufferOut.put(bb);
                updateInterestOps();
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
        private void doRead() throws IOException {
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
        private void doWrite() throws IOException {
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

    private static int BUFFER_SIZE = 10_000;
    private static Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());

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
            sendCommand("INTERNAL_ACCEPT_PRIVATE:" + pseudo);
            System.out.println("✅ Connexion privée acceptée avec " + pseudo);
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
            } else if(command.startsWith("@")) {
                String[] parts = command.substring(1).split(" ", 2);
                String pseudo = parts[0];
                
                if (!pseudo.isEmpty()) {
                    logger.info("Demande de message privé pour: " + pseudo);
                    uniqueContext.queuePrivateRequest(pseudo);
                } else {
                    logger.info("Pseudo manquant après @");
                }
            } else if(command.startsWith("/")) {
                if(command.equals("/getusers")) {
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
        uniqueContext = new Context(key, login);
        key.attach(uniqueContext);
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
            if (key.isValid() && key.isConnectable()) {
                uniqueContext.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                uniqueContext.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
            	logger.info("🔍 Données disponibles en lecture");
                uniqueContext.doRead();
            }
        } catch (IOException ioe) {
            // lambda call in select requires to tunnel IOException
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