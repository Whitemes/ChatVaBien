package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Serveur ChatVaBien
 */

public class ChatVaBienServer {
    private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    private final Map<String, User> connectedUsers = new ConcurrentHashMap<>();

    private final SocketChannel mdpChannel;
    private final Map<Long, Context> pendingAuthRequests = new ConcurrentHashMap<>();

    public ChatVaBienServer(int port, InetSocketAddress mdpAddress) throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.bind(new InetSocketAddress(port));
        this.serverSocketChannel.configureBlocking(false);

        this.selector = Selector.open();
        this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        if (mdpAddress != null) {
            this.mdpChannel = SocketChannel.open();
            this.mdpChannel.configureBlocking(false);
            this.mdpChannel.connect(mdpAddress);
            this.mdpChannel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
        } else {
            this.mdpChannel = null;
        }
    }

    public void launch() throws IOException {
        logger.info("Serveur ChatVaBien démarré");

        while (!Thread.interrupted()) {
            selector.select(this::treatKey);
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            if (key.channel() == mdpChannel) {
                handleMDPKey(key);
                return;
            }

            if (key.isValid() && key.isAcceptable()) {
                doAccept(key);
            }
            if (key.isValid() && key.isWritable()) {
                ((Context) key.attachment()).doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                ((Context) key.attachment()).doRead();
            }
        } catch (IOException e) {
            logger.warning("Connexion fermée: " + e.getMessage());
            silentlyClose(key);
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        var clientChannel = serverSocketChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        var clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
        clientKey.attach(new Context(clientKey));

        logger.info("Nouvelle connexion acceptée");
    }

    private void handleMDPKey(SelectionKey key) throws IOException {
        if (key.isConnectable()) {
            if (mdpChannel.finishConnect()) {
                logger.info("Connecté au serveur MDP");
            }
        }
        if (key.isReadable()) {
            handleMDPResponse();
        }
    }

    private void handleMDPResponse() throws IOException {
        var buffer = ByteBuffer.allocate(1024);
        var read = mdpChannel.read(buffer);
        if (read == -1) {
            throw new IOException("Connexion MDP fermée");
        }

        buffer.flip();
        while (buffer.remaining() >= 9) {
            var status = buffer.get();
            var id = buffer.getLong();
            var context = pendingAuthRequests.remove(id);
            if (context != null) {
                context.onAuthResponse(status == 1);
            }
        }
    }

    private void silentlyClose(SelectionKey key) {
        try {
            var context = (Context) key.attachment();
            if (context != null) {
                context.cleanup();
            }
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Contexte client simplifié
     */
    private class Context implements ServerMessageProcessor {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Queue<Trame> outQueue = new ArrayDeque<>();

        private final TrameReader trameReader = new TrameReader();

        private String pseudo;
        boolean authenticated = false;
        private boolean closed = false;

        Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        void doRead() throws IOException {
            var read = sc.read(bufferIn);

            if (read == -1) {
                closed = true;
                return;
            }

            if (read > 0) {
                processIn();
            }

            updateInterestOps();
        }

        private void processIn() {
            bufferIn.flip();

            while (true) {
                var status = trameReader.process(bufferIn);

                if (status == Reader.ProcessStatus.DONE) {
                    var trame = trameReader.get();
                    handleTrame(trame);
                    trameReader.reset();
                } else if (status == Reader.ProcessStatus.REFILL) {
                    break;
                } else {
                    logger.severe("Erreur parsing - abandon");
                    closed = true;
                    break;
                }
            }

            bufferIn.compact();
        }

        void doWrite() throws IOException {
            processOut();

            if (bufferOut.position() > 0) {
                bufferOut.flip();
                var written = sc.write(bufferOut);

                if (bufferOut.hasRemaining()) {
                    bufferOut.compact();
                } else {
                    bufferOut.clear();
                }
            }

            updateInterestOps();
        }

        private void handleTrame(Trame trame) {
            if (pseudo == null) {
                pseudo = trame.sender();
            } else if (!pseudo.equals(trame.sender()) && !trame.sender().equals("Server") && !trame.sender().isBlank()) {
                logger.warning("Pseudo incohérent: reçu '" + trame.sender() + "', attendu '" + pseudo + "'");
                return;
            }

            trame.message().process(this);
        }

        private void processOut() {
            while (bufferOut.hasRemaining() && !outQueue.isEmpty()) {
                var trame = outQueue.peek();
                var buffer = trame.toByteBuffer();

                if (bufferOut.remaining() >= buffer.remaining()) {
                    bufferOut.put(buffer);
                    outQueue.poll();
                } else {
                    break;
                }
            }
        }

        private void queueResponse(OPCODE opcode, Message message) {
            outQueue.offer(Trame.serverResponse(opcode, message));
            updateInterestOps();
        }

        private void updateInterestOps() {
            var ops = SelectionKey.OP_READ;
            if (!outQueue.isEmpty() || bufferOut.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            key.interestOps(ops);
        }

        void cleanup() {
            if (pseudo != null && authenticated) {
                connectedUsers.remove(pseudo);
                broadcastUserDisconnection(pseudo);
            }
        }

        void onAuthResponse(boolean success) {
            if (success && !connectedUsers.containsKey(pseudo)) {
                var user = new User(System.currentTimeMillis(), pseudo, sc, true);
                connectedUsers.put(pseudo, user);
                authenticated = true;
                queueResponse(OPCODE.LOGIN_ACCEPTED, new LoginMessage());
                broadcastUserConnection(pseudo);
            } else {
                queueResponse(OPCODE.LOGIN_REFUSED, new LoginMessage());
            }
        }

        @Override
        public void processLogin() {
            if (connectedUsers.containsKey(pseudo)) {
                queueResponse(OPCODE.LOGIN_REFUSED, new LoginMessage());
            } else {
                var user = new User(System.currentTimeMillis(), pseudo, sc, false);
                connectedUsers.put(pseudo, user);
                authenticated = true;

                queueResponse(OPCODE.LOGIN_ACCEPTED, new LoginMessage());

                logger.info(pseudo + " s'est connecté");
                broadcastUserConnection(pseudo);
            }
        }

        @Override
        public void processPublicMessage(String text) {
            if (!authenticated) {
                logger.warning("Message non authentifié de " + pseudo);
                return;
            }

            logger.info("Message public: " + pseudo + " -> " + text);
            broadcast(pseudo, text);
        }

        @Override
        public void processPrivateRequest(String targetPseudo) {
            logger.info("Demande privée: " + pseudo + " -> " + targetPseudo);

            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) {
                logger.warning("Utilisateur cible introuvable: " + targetPseudo);
                return;
            }

            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var requestTrame = Trame.clientMessage(OPCODE.REQUEST_PRIVATE, pseudo, new PrivateRequestMessage(targetPseudo));
                targetContext.outQueue.offer(requestTrame);
                targetContext.updateInterestOps();
                logger.info("Demande privée transmise: " + pseudo + " -> " + targetPseudo);
            }
        }

        @Override
        public void processOKPrivate(String targetPseudo, InetSocketAddress address, long token) {
            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) return;

            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var responseTrame = Trame.clientMessage(OPCODE.OK_PRIVATE, pseudo, new PublicMessage(targetPseudo));
                targetContext.outQueue.offer(responseTrame);
                targetContext.updateInterestOps();
            }
        }

        @Override
        public void processKOPrivate(String targetPseudo) {
            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) return;

            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var responseTrame = Trame.clientMessage(OPCODE.KO_PRIVATE, pseudo, new PublicMessage(targetPseudo));
                targetContext.outQueue.offer(responseTrame);
                targetContext.updateInterestOps();
            }
        }

        @Override
        public void processGetUsers() {
            var userList = String.join(", ", connectedUsers.keySet());
            var response = new PublicMessage(userList);
            queueResponse(OPCODE.CONNECTED_USERS_LIST, response);
            logger.info("Liste utilisateurs envoyée à " + pseudo + ": " + userList);
        }
    }

    private void broadcast(String sender, String message) {
        var broadcastMessage = new PublicMessage(message);
        var trame = Trame.clientMessage(OPCODE.MESSAGE, sender, broadcastMessage);

        int envoyesCount = 0;
        for (var user : connectedUsers.values()) {
            var key = user.sc().keyFor(selector);
            if (key != null) {
                var context = (Context) key.attachment();
                if (context != null && context.authenticated) {
                    context.outQueue.offer(trame);
                    context.updateInterestOps();
                    envoyesCount++;
                }
            }
        }

        logger.info("Message diffusé de " + sender + " à " + envoyesCount + " utilisateurs");
    }

    private void broadcastUserConnection(String pseudo) {
        broadcast("Server", pseudo + " s'est connecté");
    }

    private void broadcastUserDisconnection(String pseudo) {
        broadcast("Server", pseudo + " s'est déconnecté");
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java ChatVaBienServer <port> [mdpPort]");
            return;
        }

        var serverPort = Integer.parseInt(args[0]);
        InetSocketAddress mdpAddress = null;

        if (args.length >= 2) {
            var mdpPort = Integer.parseInt(args[1]);
            mdpAddress = new InetSocketAddress("localhost", mdpPort);
        }

        new ChatVaBienServer(serverPort, mdpAddress).launch();
    }
}