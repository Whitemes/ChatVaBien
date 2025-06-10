package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Client ChatVaBien modularisé - Point d'entrée principal
 */
public class ChatVaBienClient implements ServerMessageHandler {
    private static final Logger logger = Logger.getLogger(ChatVaBienClient.class.getName());

    private final String login;
    private final Path fileDirectory;
    private final InetSocketAddress serverAddress;

    // Composants modulaires
    private final Selector selector;
    private final Map<SelectionKey, ChannelHandler> handlers = new HashMap<>();
    private final Map<String, PrivateContext> privateContexts = new HashMap<>();
    private final ConsoleManager consoleManager = new ConsoleManager();

    // Contextes principaux
    private ServerContext serverContext;
    private ServerSocketChannel privateServerChannel;
    private CommandProcessor commandProcessor;

    public ChatVaBienClient(String login, InetSocketAddress serverAddress, Path fileDirectory) throws IOException {
        this.login = login;
        this.serverAddress = serverAddress;
        this.fileDirectory = fileDirectory;
        this.selector = Selector.open();
    }

    public void launch() throws IOException {
        setupServerConnection();
        setupPrivateServer();
        setupCommandProcessor();
        consoleManager.start();

        logger.info("Client ChatVaBien démarré pour " + login);

        while (!Thread.interrupted()) {
            // ✅ CORRECTION: Timeout pour ne pas bloquer indéfiniment
            selector.select(1000); // 1 seconde timeout

            var selectedKeys = selector.selectedKeys();
            var iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                var key = iterator.next();
                iterator.remove();
                treatKey(key);
            }

            // ✅ CORRECTION: Traiter les commandes même sans événements réseau
            processCommands();
        }
    }

    // ========== SETUP ==========

    private void setupServerConnection() throws IOException {
        var serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);

        var key = serverChannel.register(selector, SelectionKey.OP_CONNECT);
        this.serverContext = new ServerContext(key, login, this);
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
                var clientKey = clientChannel.register(selector, SelectionKey.OP_READ);

                var context = new PrivateContext(clientKey, login, null);
                handlers.put(clientKey, context);

                logger.info("Connexion privée entrante acceptée");
            }
        });

        var privatePort = ((InetSocketAddress) privateServerChannel.getLocalAddress()).getPort();
        logger.info("Serveur privé sur port " + privatePort);
    }

    private void setupCommandProcessor() {
        this.commandProcessor = new DefaultCommandProcessor(
                serverContext,
                privateContexts,
                privateServerChannel
        );
    }

    // ========== TRAITEMENT ÉVÉNEMENTS ==========

    private void treatKey(SelectionKey key) {
        try {
            var handler = handlers.get(key);
            if (handler == null) {
                logger.warning("Handler null pour clé: " + key.channel());
                return;
            }

            logger.info("Traitement clé: " + key.readyOps() + " pour " + key.channel().getClass().getSimpleName());

            if (key.isValid() && key.isConnectable()) {
                logger.info("Connexion...");
                handler.handleConnect();
            }
            if (key.isValid() && key.isAcceptable()) {
                logger.info("Acceptation...");
                handler.handleAccept();
            }
            if (key.isValid() && key.isWritable()) {
                logger.info("Écriture...");
                handler.handleWrite();
            }
            if (key.isValid() && key.isReadable()) {
                logger.info("Lecture...");
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

    // ========== TRAITEMENT COMMANDES ==========

    private void processCommands() {
        String command;
        boolean hasCommand = false;

        while ((command = consoleManager.pollCommand()) != null) {
            logger.info("Traitement commande: '" + command + "'"); // ✅ DEBUG
            commandProcessor.processCommand(command);
            hasCommand = true;
        }

        // ✅ AJOUT: Log si aucune commande
        if (!hasCommand) {
            // Pas de log pour éviter le spam - juste pour debug initial
            // logger.info("Aucune commande en attente");
        }
    }

    // ========== GESTION MESSAGES SERVEUR ==========

    @Override
    public void handleServerMessage(Trame trame) {
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
                handlePrivateRequest(trame.sender());
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
            var context = new PrivateContext(key, login, sender);
            context.setOutgoingToken(token);

            handlers.put(key, context);
            privateContexts.put(sender, context);

            privateChannel.connect(address);

        } catch (IOException e) {
            System.err.println("❌ Erreur connexion privée: " + e.getMessage());
        }
    }

    // ========== TEST RAPIDE - Ajoutez cette méthode dans ChatVaBienClient ==========

    /**
     * Méthode de test pour vérifier que la console fonctionne
     */
    private void testConsole() {
        System.out.println("=== TEST CONSOLE ===");

        // Test direct de la queue
        consoleManager.start();

        // Simuler des commandes
        var testCommands = new java.util.concurrent.ArrayBlockingQueue<String>(10);
        testCommands.offer("hello");
        testCommands.offer("/help");

        // Vérifier le polling
        for (int i = 0; i < 5; i++) {
            var cmd = consoleManager.pollCommand();
            if (cmd != null) {
                System.out.println("Commande reçue: " + cmd);
            } else {
                System.out.println("Pas de commande (cycle " + i + ")");
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }

        System.out.println("=== FIN TEST ===");
    }

    // ========== DANS MAIN, AJOUTEZ AVANT launch() ==========
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
        var client = new ChatVaBienClient(login, serverAddress, fileDir);

        // ✅ TEST: Décommentez pour tester la console
//         client.testConsole();
//         return;

        client.launch();
    }
}