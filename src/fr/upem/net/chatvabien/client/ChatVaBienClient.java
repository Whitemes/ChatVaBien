package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Client ChatVaBien refactorisé - architecture propre et non-bloquante
 */
public class ChatVaBienClient {
    private static final Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final String login;
    private final Path fileDirectory;
    private final InetSocketAddress serverAddress;

    // Connexions et sélection
    private final Selector selector;
    private final Map<SelectionKey, ChannelHandler> handlers = new HashMap<>();

    // Contexte serveur principal
    private ServerContext serverContext;

    // Contextes privés
    private final Map<String, PrivateContext> privateContexts = new HashMap<>();
    private ServerSocketChannel privateServerChannel;
    private int privatePort;

    // Communication avec console
    private final BlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);
    private final Thread consoleThread;

    public ChatVaBienClient(String login, InetSocketAddress serverAddress, Path fileDirectory) throws IOException {
        this.login = login;
        this.serverAddress = serverAddress;
        this.fileDirectory = fileDirectory;
        this.selector = Selector.open();
        this.consoleThread = Thread.ofPlatform().daemon().unstarted(this::consoleRun);
    }

    public void launch() throws IOException {
        setupServerConnection();
        setupPrivateServer();
        consoleThread.start();

        logger.info("Client ChatVaBien démarré pour " + login);

        while (!Thread.interrupted()) {
            selector.select(this::treatKey);
            processCommands();
        }
    }

    // ========== SETUP ==========

    private void setupServerConnection() throws IOException {
        var serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);

        var key = serverChannel.register(selector, SelectionKey.OP_CONNECT);
        this.serverContext = new ServerContext(key);
        handlers.put(key, serverContext);

        serverChannel.connect(serverAddress);
    }

    private void setupPrivateServer() throws IOException {
        privateServerChannel = ServerSocketChannel.open();
        privateServerChannel.configureBlocking(false);
        privateServerChannel.bind(new InetSocketAddress(0));

        var key = privateServerChannel.register(selector, SelectionKey.OP_ACCEPT);
        handlers.put(key, new ChannelHandler() {
            @Override
            public void handleAccept() throws IOException {
                var clientChannel = privateServerChannel.accept();
                if (clientChannel == null) return;

                clientChannel.configureBlocking(false);
                var key = clientChannel.register(selector, SelectionKey.OP_READ);

                // Contexte temporaire en attente du token OPEN
                var context = new PrivateContext(key, null);
                handlers.put(key, context);

                logger.info("Connexion privée entrante acceptée");
            }
        });

        this.privatePort = ((InetSocketAddress) privateServerChannel.getLocalAddress()).getPort();
        logger.info("Serveur privé sur port " + privatePort);
    }

    // ========== TRAITEMENT DES ÉVÉNEMENTS ==========

    private void treatKey(SelectionKey key) {
        try {
            var handler = handlers.get(key);
            if (handler == null) return;

            if (key.isValid() && key.isConnectable()) {
                handler.handleConnect();
            }
            if (key.isValid() && key.isAcceptable()) {
                handler.handleAccept();
            }
            if (key.isValid() && key.isWritable()) {
                handler.handleWrite();
            }
            if (key.isValid() && key.isReadable()) {
                handler.handleRead();
            }
        } catch (IOException e) {
            logger.warning("Erreur sur clé: " + e.getMessage());
            silentlyClose(key);
        }
    }

    private void silentlyClose(SelectionKey key) {
        try {
            handlers.remove(key);
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    // ========== INTERFACE HANDLER ==========

    private interface ChannelHandler {
        default void handleConnect() throws IOException {}
        default void handleAccept() throws IOException {}
        default void handleRead() throws IOException {}
        default void handleWrite() throws IOException {}
    }

    // Handlers simples pour les cas où on n'a qu'une méthode
    private void handlePrivateAccept() throws IOException {
        var clientChannel = privateServerChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        var key = clientChannel.register(selector, SelectionKey.OP_READ);

        // Contexte temporaire en attente du token OPEN
        var context = new PrivateContext(key, null);
        handlers.put(key, context);

        logger.info("Connexion privée entrante acceptée");
    }

    // ========== CONTEXTE SERVEUR ==========

    private class ServerContext implements ChannelHandler {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Queue<Trame> outQueue = new ArrayDeque<>();

        private final TrameReader trameReader = new TrameReader();
        private boolean loginSent = false;
        private boolean connected = false;

        ServerContext(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        @Override
        public void handleConnect() throws IOException {
            if (sc.finishConnect()) {
                connected = true;
                updateInterestOps();
                logger.info("Connecté au serveur");
            }
        }

        @Override
        public void handleRead() throws IOException {
            var read = sc.read(bufferIn);
            if (read == -1) {
                logger.info("Serveur fermé");
                return;
            }

            if (read > 0) {
                processIn();
            }
        }

        @Override
        public void handleWrite() throws IOException {
            if (!loginSent) {
                sendLogin();
            }

            processOut();

            logger.info("Buffer avant écriture - position: " + bufferOut.position() + ", limit: " + bufferOut.limit());
            bufferOut.flip();
            var written = sc.write(bufferOut);
            logger.info("Bytes écrits: " + written);
            bufferOut.compact();

            updateInterestOps();
        }

        private void processIn() {
            bufferIn.flip();

            while (true) {
                var status = trameReader.process(bufferIn);
                if (status == Reader.ProcessStatus.DONE) {
                    handleServerMessage(trameReader.get());
                    trameReader.reset();
                } else if (status == Reader.ProcessStatus.REFILL) {
                    break;
                } else {
                    logger.severe("Erreur lecture serveur");
                    break;
                }
            }

            bufferIn.compact();
        }

        private void handleServerMessage(Trame trame) {
            switch (trame.opcode()) {
                case LOGIN_ACCEPTED -> {
                    System.out.println("✅ Connexion acceptée");
                }
                case LOGIN_REFUSED -> {
                    System.out.println("❌ Connexion refusée");
                    System.exit(1);
                }
                case MESSAGE -> {
                    if (trame.message() instanceof PublicMessage msg) {
                        System.out.println(trame.sender() + ": " + msg.text());
                    }
                }
                case REQUEST_PRIVATE -> {
                    if (trame.message() instanceof PrivateRequestMessage req) {
                        handlePrivateRequest(trame.sender());
                    }
                }
                case OK_PRIVATE -> {
                    if (trame.message() instanceof OKPrivateMessage msg) {
                        handleOKPrivate(trame.sender(), msg.address(), msg.token());
                    }
                }
                case KO_PRIVATE -> {
                    System.out.println("❌ Connexion privée refusée par " + trame.sender());
                }
                case CONNECTED_USERS_LIST -> {
                    if (trame.message() instanceof PublicMessage msg) {
                        System.out.println("👥 Utilisateurs connectés:\n" + msg.text());
                    }
                }
                default -> logger.warning("Message serveur non géré: " + trame.opcode());
            }
        }

        private void sendLogin() {
            if (loginSent || !connected) return;

            var loginTrame = Trame.clientMessage(OPCODE.LOGIN, login, new LoginMessage());
            var buffer = loginTrame.toByteBuffer();
            logger.info("Trame LOGIN créée - taille: " + buffer.remaining());
            logger.info("Login: '" + login + "'");
            outQueue.offer(loginTrame);
            loginSent = true;
            updateInterestOps();
        }
        private void processOut() {
            logger.info("=== DEBUT processOut ===");
            logger.info("Queue size: " + outQueue.size());
            logger.info("BufferOut remaining: " + bufferOut.remaining());

            while (bufferOut.hasRemaining() && !outQueue.isEmpty()) {
                var trame = outQueue.peek();
                var buffer = trame.toByteBuffer();

                logger.info("Traitement trame - taille: " + buffer.remaining());

                if (bufferOut.remaining() >= buffer.remaining()) {
                    bufferOut.put(buffer);
                    outQueue.poll();
                    logger.info("Trame ajoutée au buffer, queue size: " + outQueue.size());
                } else {
                    logger.info("Pas assez de place dans bufferOut");
                    break;
                }
            }
            logger.info("BufferOut position après processOut: " + bufferOut.position());
        }

        void queueMessage(Trame trame) {
            outQueue.offer(trame);
            updateInterestOps();
        }

        private void updateInterestOps() {
            var ops = SelectionKey.OP_READ;
            if (!loginSent || !outQueue.isEmpty() || bufferOut.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            key.interestOps(ops);
        }
    }

    // ========== GESTION CONNEXIONS PRIVÉES ==========

    private void handlePrivateRequest(String requester) {
        System.out.println("📨 Demande de connexion privée de " + requester);
        System.out.println("Tapez 'accept " + requester + "' ou 'refuse " + requester + "'");
    }

    private void handleOKPrivate(String sender, InetSocketAddress address, long token) {
        System.out.println("✅ " + sender + " a accepté votre demande privée");

        try {
            var privateChannel = SocketChannel.open();
            privateChannel.configureBlocking(false);

            var key = privateChannel.register(selector, SelectionKey.OP_CONNECT);
            var context = new PrivateContext(key, sender);
            context.setOutgoingToken(token);
            handlers.put(key, context);
            privateContexts.put(sender, context);

            privateChannel.connect(address);

        } catch (IOException e) {
            System.err.println("❌ Erreur connexion privée: " + e.getMessage());
        }
    }

    // ========== CONTEXTE PRIVÉ ==========

    private class PrivateContext implements ChannelHandler {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Queue<ByteBuffer> outQueue = new ArrayDeque<>();

        private String remotePseudo;
        private long expectedToken = -1;
        private long outgoingToken = -1;
        private boolean opened = false;

        PrivateContext(SelectionKey key, String remotePseudo) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.remotePseudo = remotePseudo;
        }

        void setOutgoingToken(long token) {
            this.outgoingToken = token;
        }

        void setExpectedToken(long token) {
            this.expectedToken = token;
        }

        @Override
        public void handleConnect() throws IOException {
            if (sc.finishConnect()) {
                sendOpen();
                updateInterestOps();
            }
        }

        @Override
        public void handleRead() throws IOException {
            var read = sc.read(bufferIn);
            if (read == -1) {
                System.out.println("💔 Connexion privée fermée avec " + remotePseudo);
                return;
            }

            if (read > 0) {
                processPrivateIn();
            }
        }

        @Override
        public void handleWrite() throws IOException {
            processPrivateOut();

            bufferOut.flip();
            sc.write(bufferOut);
            if (bufferOut.hasRemaining()) {
                bufferOut.compact();  // Seulement si il reste des données
            } else {
                bufferOut.clear();    // Sinon, on vide complètement
            }

            updateInterestOps();
        }

        private void sendOpen() {
            if (outgoingToken != -1) {
                var openBuffer = ByteBuffer.allocate(9);
                openBuffer.put(OPCODE.OPEN.getCode());
                openBuffer.putLong(outgoingToken);
                openBuffer.flip();
                outQueue.offer(openBuffer);
            }
        }

        private void processPrivateIn() {
            bufferIn.flip();

            while (bufferIn.hasRemaining()) {
                if (!opened) {
                    if (bufferIn.remaining() < 9) break;

                    var opcode = bufferIn.get();
                    if (opcode == OPCODE.OPEN.getCode()) {
                        var token = bufferIn.getLong();
                        if (expectedToken == -1 || token == expectedToken) {
                            opened = true;
                            System.out.println("🔗 Connexion privée établie avec " + remotePseudo);
                        } else {
                            logger.warning("Token invalide");
                            return;
                        }
                    }
                } else {
                    // Traiter messages privés (MESSAGE ou FILE)
                    if (bufferIn.remaining() < 1) break;

                    var opcode = bufferIn.get();
                    if (opcode == OPCODE.MESSAGE.getCode()) {
                        // Parse message privé simple
                        // Format: senderSize + sender + textSize + text
                        var stringReader = new StringReader();
                        if (parsePrivateMessage()) {
                            // Message traité
                        } else {
                            bufferIn.position(bufferIn.position() - 1);
                            break;
                        }
                    }
                    // TODO: Gestion FILE
                }
            }

            bufferIn.compact();
        }

        private boolean parsePrivateMessage() {
            // Simplification pour l'exemple
            // En réalité, utiliser StringReader pour parser correctement
            return false;
        }

        private void processPrivateOut() {
            while (bufferOut.hasRemaining() && !outQueue.isEmpty()) {
                var buffer = outQueue.peek();

                if (bufferOut.remaining() >= buffer.remaining()) {
                    bufferOut.put(buffer);
                    outQueue.poll();
                } else {
                    break;
                }
            }
        }

        void sendPrivateMessage(String message) {
            if (!opened) {
                System.out.println("❌ Connexion privée pas encore établie");
                return;
            }

            var msgBytes = message.getBytes();
            var buffer = ByteBuffer.allocate(1 + 4 + login.length() + 4 + msgBytes.length);
            buffer.put(OPCODE.MESSAGE.getCode());
            buffer.putInt(login.length());
            buffer.put(login.getBytes());
            buffer.putInt(msgBytes.length);
            buffer.put(msgBytes);
            buffer.flip();

            outQueue.offer(buffer);
            updateInterestOps();
        }

        private void updateInterestOps() {
            var ops = SelectionKey.OP_READ;
            if (!outQueue.isEmpty() || bufferOut.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            key.interestOps(ops);
        }
    }

    // ========== CONSOLE ET COMMANDES ==========

    private void consoleRun() {
        try (var scanner = new Scanner(System.in)) {
            System.out.println("🚀 Client démarré. Tapez vos messages ou /help pour l'aide");

            while (scanner.hasNextLine()) {
                var line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    commandQueue.offer(line);
                    selector.wakeup();
                }
            }
        }
    }

    private void processCommands() {
        String command;
        while ((command = commandQueue.poll()) != null) {
            handleCommand(command);
        }
    }

    private void handleCommand(String command) {
        try {
            if (command.startsWith("/")) {
                handleSpecialCommand(command);
            } else if (command.startsWith("@")) {
                handlePrivateMessage(command);
            } else if (command.startsWith("accept ")) {
                handleAcceptPrivate(command.substring(7));
            } else if (command.startsWith("refuse ")) {
                handleRefusePrivate(command.substring(7));
            } else {
                handlePublicMessage(command);
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur commande: " + e.getMessage());
        }
    }

    private void handleSpecialCommand(String command) {
        switch (command) {
            case "/help" -> showHelp();
            case "/users" -> requestUserList();
            case "/quit" -> System.exit(0);
            default -> {
                if (command.startsWith("/")) {
                    System.out.println("❓ Commande inconnue. Tapez /help");
                }
            }
        }
    }

    private void handlePublicMessage(String message) {
        var publicMsg = new PublicMessage(message);
        var trame = Trame.clientMessage(OPCODE.MESSAGE, login, publicMsg);
        serverContext.queueMessage(trame);
    }

    private void handlePrivateMessage(String command) {
        var parts = command.substring(1).split(" ", 2);
        if (parts.length != 2) {
            System.out.println("❓ Usage: @pseudo message");
            return;
        }

        var targetPseudo = parts[0];
        var message = parts[1];

        var privateContext = privateContexts.get(targetPseudo);
        if (privateContext != null && privateContext.opened) {
            privateContext.sendPrivateMessage(message);
            System.out.println("💬 [PRIVÉ] -> " + targetPseudo + ": " + message);
        } else {
            // Demander connexion privée
            var request = new PrivateRequestMessage(targetPseudo);
            var trame = Trame.clientMessage(OPCODE.REQUEST_PRIVATE, login, request);
            serverContext.queueMessage(trame);
            System.out.println("📤 Demande de connexion privée envoyée à " + targetPseudo);
        }
    }

    private void handleAcceptPrivate(String requester) {
        try {
            var localAddress = (InetSocketAddress) privateServerChannel.getLocalAddress();
            var token = System.currentTimeMillis();

            // Préparer contexte pour connexion entrante
            // TODO: Associer token à l'utilisateur

            var response = new OKPrivateMessage(requester, localAddress, token);
            var trame = Trame.clientMessage(OPCODE.OK_PRIVATE, login, response);
            serverContext.queueMessage(trame);

            System.out.println("✅ Connexion privée acceptée avec " + requester);

        } catch (IOException e) {
            System.err.println("❌ Erreur acceptation privée: " + e.getMessage());
        }
    }

    private void handleRefusePrivate(String requester) {
        var response = new KOPrivateMessage(requester);
        var trame = Trame.clientMessage(OPCODE.KO_PRIVATE, login, response);
        serverContext.queueMessage(trame);

        System.out.println("❌ Connexion privée refusée avec " + requester);
    }

    private void requestUserList() {
        var request = new GetUsersMessage();
        var trame = Trame.clientMessage(OPCODE.GET_CONNECTED_USERS, login, request);
        serverContext.queueMessage(trame);
    }

    private void showHelp() {
        System.out.println("""
            🆘 Aide ChatVaBien:
            
            Messages publics:
              <message>           - Envoyer un message public
            
            Messages privés:
              @pseudo <message>   - Message privé (crée connexion si nécessaire)
              accept <pseudo>     - Accepter demande de connexion privée
              refuse <pseudo>     - Refuser demande de connexion privée
            
            Commandes:
              /users              - Lister les utilisateurs connectés
              /help               - Afficher cette aide
              /quit               - Quitter
            """);
    }

    // ========== POINT D'ENTRÉE ==========

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("Usage: java ChatVaBienClient <login> <host> <port> <fileDir>");
            return;
        }

        var login = args[0];
        var host = args[1];
        var port = Integer.parseInt(args[2]);
        var fileDir = Path.of(args[3]);

        var serverAddress = new InetSocketAddress(host, port);

        new ChatVaBienClient(login, serverAddress, fileDir).launch();
    }
}