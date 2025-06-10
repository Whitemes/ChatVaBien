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
 * Serveur ChatVaBien refactorisé selon les bonnes pratiques NIO
 */
public class ChatVaBienServer {
    private static final Logger logger = Logger.getLogger(ChatVaBienServer.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    private final Map<String, User> connectedUsers = new ConcurrentHashMap<>();

    // Connexion MDP optionnelle
    private final SocketChannel mdpChannel;
    private final Map<Long, Context> pendingAuthRequests = new ConcurrentHashMap<>();

    public ChatVaBienServer(int port, InetSocketAddress mdpAddress) throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.bind(new InetSocketAddress(port));
        this.serverSocketChannel.configureBlocking(false);

        this.selector = Selector.open();
        this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        // Connexion MDP optionnelle
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
        while (buffer.remaining() >= 9) { // 1 byte status + 8 bytes id
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
     * Contexte client simplifié - plus d'interface inutile
     */
    private class Context implements ServerMessageProcessor {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final Queue<Trame> outQueue = new ArrayDeque<>();

        private final TrameReader trameReader = new TrameReader();

        private String pseudo;
        private boolean authenticated = false;
        private boolean closed = false;

        Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        // ✅ CORRIGÉ: Une seule méthode dumpBuffer
        private static String dumpBuffer(ByteBuffer buffer) {
            StringBuilder sb = new StringBuilder();

            // ✅ IMPORTANT: Sauvegarder position/limit
            int originalPos = buffer.position();
            int originalLimit = buffer.limit();

            // ✅ Afficher depuis le début jusqu'à la position (données écrites)
            for (int i = 0; i < originalPos; i++) {
                byte b = buffer.get(i);
                sb.append(String.format("%02X ", b));
            }

            // ✅ Si en read-mode, afficher aussi les données restantes
            if (originalPos < originalLimit) {
                sb.append("| ");
                for (int i = originalPos; i < originalLimit; i++) {
                    byte b = buffer.get(i);
                    sb.append(String.format("%02X ", b));
                }
            }

            return sb.toString().trim();
        }

        void doRead() throws IOException {
            // ✅ AJOUT: Debug de l'état initial du buffer
            logger.info("=== DÉBUT doRead ===");
            logger.info("bufferIn AVANT lecture - position: " + bufferIn.position() + ", limit: " + bufferIn.limit() + ", remaining: " + bufferIn.remaining());

            var read = sc.read(bufferIn);
            logger.info("Bytes lus du réseau: " + read);

            if (read == -1) {
                closed = true;
                return;
            }

            if (read > 0) {
                // ✅ AJOUT: Debug APRÈS lecture, AVANT processIn
                logger.info("bufferIn APRÈS lecture - position: " + bufferIn.position() + ", limit: " + bufferIn.limit() + ", remaining: " + bufferIn.remaining());
                logger.info("Contenu bufferIn RAW APRÈS lecture: " + dumpBuffer(bufferIn));

                processIn();

                // ✅ AJOUT: Debug APRÈS processIn
                logger.info("bufferIn APRÈS processIn - position: " + bufferIn.position() + ", limit: " + bufferIn.limit() + ", remaining: " + bufferIn.remaining());
            }

            updateInterestOps();
            logger.info("=== FIN doRead ===");
        }

        // ✅ CORRIGÉ: UNE SEULE méthode processIn avec debug
        private void processIn() {
            logger.info("=== DÉBUT processIn ===");
            logger.info("bufferIn AVANT flip - position: " + bufferIn.position() + ", limit: " + bufferIn.limit());

            bufferIn.flip();

            logger.info("bufferIn APRÈS flip - position: " + bufferIn.position() + ", limit: " + bufferIn.limit() + ", remaining: " + bufferIn.remaining());
            logger.info("Contenu bufferIn APRÈS flip: " + dumpBuffer(bufferIn));

            while (true) {
                logger.info("--- Début boucle parsing ---");
                logger.info("bufferIn dans boucle - position: " + bufferIn.position() + ", remaining: " + bufferIn.remaining());

                var status = trameReader.process(bufferIn);

                logger.info("Status du TrameReader: " + status);
                logger.info("bufferIn après TrameReader - position: " + bufferIn.position() + ", remaining: " + bufferIn.remaining());

                if (status == Reader.ProcessStatus.DONE) {
                    var trame = trameReader.get();
                    logger.info("Trame parsée avec succès: " + trame.opcode() + " de " + trame.sender());
                    handleTrame(trame);
                    trameReader.reset();
                } else if (status == Reader.ProcessStatus.REFILL) {
                    logger.info("REFILL demandé - pas assez de données");
                    break;
                } else {
                    logger.severe("ERREUR parsing - abandon");
                    closed = true;
                    break;
                }
            }

            logger.info("bufferIn AVANT compact - position: " + bufferIn.position() + ", limit: " + bufferIn.limit() + ", remaining: " + bufferIn.remaining());
            bufferIn.compact();
            logger.info("bufferIn APRÈS compact - position: " + bufferIn.position() + ", limit: " + bufferIn.limit());
            logger.info("=== FIN processIn ===");
        }

        // ✅ AJOUT: doWrite corrigé pour éviter les problèmes
        void doWrite() throws IOException {
            logger.info("=== DÉBUT doWrite pour " + pseudo + " ===");
            logger.info("Queue size: " + outQueue.size());

            processOut();

            if (bufferOut.position() > 0) {
                logger.info("BufferOut à écrire - position: " + bufferOut.position());
                bufferOut.flip();
                var written = sc.write(bufferOut);
                logger.info("Bytes écrits: " + written);

                if (bufferOut.hasRemaining()) {
                    logger.info("Données restantes: " + bufferOut.remaining());
                    bufferOut.compact();
                } else {
                    logger.info("Buffer entièrement écrit");
                    bufferOut.clear();
                }
            } else {
                logger.info("Rien à écrire");
            }

            updateInterestOps();
            logger.info("=== FIN doWrite ===");
        }

        private void handleTrame(Trame trame) {
            // Validation pseudo
            if (pseudo == null) {
                pseudo = trame.sender();
            } else if (!pseudo.equals(trame.sender())) {
                logger.warning("Pseudo incohérent: " + trame.sender());
                closed = true;
                return;
            }

            // Traitement polymorphe du message
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
            logger.info("Ajout réponse en queue: " + opcode + " pour " + pseudo);
            outQueue.offer(Trame.serverResponse(opcode, message));
            updateInterestOps();
            logger.info("Queue size maintenant: " + outQueue.size());
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

        // ========== IMPLÉMENTATION ServerMessageProcessor ==========

        @Override
        public void processLogin() {
            if (connectedUsers.containsKey(pseudo)) {
                queueResponse(OPCODE.LOGIN_REFUSED, new LoginMessage());
            } else {
                // Authentification simple sans MDP
                var user = new User(System.currentTimeMillis(), pseudo, sc, false);
                connectedUsers.put(pseudo, user);
                authenticated = true;
                queueResponse(OPCODE.LOGIN_ACCEPTED, new LoginMessage());
                broadcastUserConnection(pseudo);
                logger.info(pseudo + " s'est connecté");
            }
        }

        // 2. ✅ CORRECTION: processPublicMessage avec debug
        @Override
        public void processPublicMessage(String text) {
            logger.info("Message public reçu de " + pseudo + ": '" + text + "'");

            if (!authenticated) {
                logger.warning("Message non authentifié de " + pseudo);
                return;
            }

            logger.info("Diffusion du message à " + connectedUsers.size() + " utilisateurs");
            broadcast(pseudo, text);
        }

        @Override
        public void processPrivateRequest(String targetPseudo) {
            logger.info("Demande privée reçue de " + pseudo + " vers " + targetPseudo);

            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) {
                logger.warning("Utilisateur cible introuvable: " + targetPseudo);
                logger.info("Utilisateurs connectés: " + connectedUsers.keySet());
                return;
            }

            logger.info("Utilisateur " + targetPseudo + " trouvé, transmission...");

            // Transmettre la demande au destinataire
            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var request = new PrivateRequestMessage(pseudo); // pseudo = demandeur
                targetContext.queueResponse(OPCODE.REQUEST_PRIVATE, request);
                logger.info("✅ Demande privée transmise de " + pseudo + " vers " + targetPseudo);
            } else {
                logger.warning("❌ Clé SelectionKey introuvable pour " + targetPseudo);
            }
        }

        @Override
        public void processOKPrivate(String targetPseudo, InetSocketAddress address, long token) {
            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) return;

            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var response = new OKPrivateMessage(pseudo, address, token);
                targetContext.queueResponse(OPCODE.OK_PRIVATE, response);
            }
        }

        @Override
        public void processKOPrivate(String targetPseudo) {
            var targetUser = connectedUsers.get(targetPseudo);
            if (targetUser == null) return;

            var targetKey = targetUser.sc().keyFor(selector);
            if (targetKey != null) {
                var targetContext = (Context) targetKey.attachment();
                var response = new KOPrivateMessage(pseudo);
                targetContext.queueResponse(OPCODE.KO_PRIVATE, response);
            }
        }

        @Override
        public void processGetUsers() {
            logger.info("Demande liste utilisateurs de " + pseudo);

            var userList = String.join(", ", connectedUsers.keySet());
            logger.info("Liste construite: " + userList);

            var response = new PublicMessage(userList);
            queueResponse(OPCODE.CONNECTED_USERS_LIST, response);

            logger.info("✅ Réponse /users envoyée à " + pseudo);
        }
    }

    // ========== MÉTHODES DE BROADCAST ==========

    private void broadcast(String sender, String message) {
        logger.info("=== DÉBUT BROADCAST ===");
        logger.info("Sender: " + sender + ", Message: '" + message + "'");
        logger.info("Utilisateurs connectés: " + connectedUsers.keySet());

        var broadcastMessage = new PublicMessage(message);
        var trame = Trame.clientMessage(OPCODE.MESSAGE, sender, broadcastMessage);

        int envoyesCount = 0;
        for (var user : connectedUsers.values()) {
            if (!user.pseudo().equals(sender)) {
                var key = user.sc().keyFor(selector);
                if (key != null) {
                    var context = (Context) key.attachment();
                    context.outQueue.offer(trame);
                    context.updateInterestOps();
                    envoyesCount++;
                    logger.info("✅ Message diffusé vers " + user.pseudo());
                } else {
                    logger.warning("❌ Clé introuvable pour " + user.pseudo());
                }
            }
        }

        logger.info("Messages diffusés: " + envoyesCount + "/" + (connectedUsers.size() - 1));
        logger.info("=== FIN BROADCAST ===");
    }

    private void broadcastUserConnection(String pseudo) {
        broadcast("Server", pseudo + " s'est connecté");
    }

    private void broadcastUserDisconnection(String pseudo) {
        broadcast("Server", pseudo + " s'est déconnecté");
    }

    // ========== POINT D'ENTRÉE ==========

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