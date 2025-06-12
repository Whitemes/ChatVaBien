package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Client ChatVaBien modularisé - VERSION PROPRE
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

    // ✅ AJOUT: Variables pour gérer l'affichage des utilisateurs
    private boolean shouldDisplayUserList = false;
    private String cachedUserList = "";

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
            selector.select(1000); // 1 seconde timeout

            var selectedKeys = selector.selectedKeys();
            var iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                var key = iterator.next();
                iterator.remove();
                treatKey(key);
            }

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
                this,
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

    // ========== TRAITEMENT COMMANDES ==========

    private void processCommands() {
        String command;
        while ((command = consoleManager.pollCommand()) != null) {
            commandProcessor.processCommand(command);
        }
    }

    // ========== GESTION MESSAGES SERVEUR ==========

    @Override
    public void handleServerMessage(Trame trame) {
        switch (trame.opcode()) {
            case LOGIN_ACCEPTED -> {
                System.out.println("✅ Connexion acceptée - Bienvenue " + login + " !");
                serverContext.requestUserList();
            }
            case LOGIN_REFUSED -> {
                System.out.println("❌ Connexion refusée");
                System.exit(1);
            }
            case MESSAGE -> {
                // ✅ CORRECTION: Pas d'instanceof - utiliser le sender de la trame
                System.out.println(trame.sender() + ": " + extractMessageText(trame.message()));
            }
            case REQUEST_PRIVATE -> {
                handlePrivateRequest(trame.sender());
            }
            case KO_PRIVATE -> {
                System.out.println("❌ Connexion privée refusée par " + trame.sender());
            }
            case CONNECTED_USERS_LIST -> {
                // ✅ CORRECTION: Pas d'instanceof - utiliser directement le texte
                String users = extractMessageText(trame.message());
                if (!users.trim().isEmpty()) {
                    System.out.println("Utilisateurs connectés: " + users);
                }
            }
            default -> logger.warning("Message serveur non géré: " + trame.opcode());
        }
    }

    // ✅ AJOUT: Méthode utilitaire pour extraire le texte
    private String extractMessageText(Message message) {
        // On sait que pour MESSAGE et CONNECTED_USERS_LIST, c'est toujours PublicMessage
        // Mais on évite instanceof en utilisant une méthode générique
        var buffer = message.serialize();
        if (buffer.remaining() < 4) return "";

        var length = buffer.getInt();
        if (length <= 0 || length > buffer.remaining()) return "";

        var textBytes = new byte[length];
        buffer.get(textBytes);
        return new String(textBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void handlePrivateRequest(String requester) {
        System.out.println("📨 Demande de connexion privée de " + requester);
        System.out.println("Tapez 'accept " + requester + "' ou 'refuse " + requester + "'");
    }

    // ========== AJOUT: Gestion commande /users ==========

    public void handleUsersCommand() {
        shouldDisplayUserList = true;
        serverContext.requestUserList();
    }

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

        client.launch();
    }
}