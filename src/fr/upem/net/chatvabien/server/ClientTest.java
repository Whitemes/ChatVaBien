package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.User;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientTest {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static final Charset UTF8 = Charset.forName("UTF8");
    private static final int MAX_BUFFER_SIZE = 1024;
	private static final Logger logger = Logger.getLogger(ClientTest.class.getName());
    private static String peusdo;
    private static String fileDirectory;
    private static SocketChannel MainSc;
    private static boolean isPrivateResquested = false;
    private static PrivateRequest pendingPrivateRequest = null;
    private static SocketChannel privateSocket = null;
    private static InetSocketAddress privateSocketAddress = null;
    private static long privateToken = -1;
    private static boolean privateSessionActive = false;
    private static int privatePort = -1;
    private static ServerSocketChannel privateServer = null;
    private static Selector privateSelector = null;
    private static long expectedIncomingToken = -1;
    private static PrivateContext activePrivateContext = null;
    private static boolean isSendingFile = false;
    static FileOutputStream currentFileOut = null;
    static String currentFileName = null;
    static int expectedFileSize = 0;
    static int receivedBytes = 0;
    private static String privateTargetPeusdo = null;
    private static boolean hasInitiatedPrivateRequest = false;
    private static boolean hasOpenedPrivateSocket = false;
    
    
    private static final Scanner scanner = new Scanner(System.in);
    private static final Map<SelectionKey, PrivateContext> privateContexts = new HashMap<>();
    
   private static class PrivateContext {
        final SocketChannel sc;
        final ByteBuffer bufferIn = ByteBuffer.allocate(1024);

        enum State { WAITING_OPCODE, WAITING_OPEN, WAITING_MESSAGE, WAITING_FILE }
        State state = State.WAITING_OPCODE;

        byte currentOpcode;
        long expectedToken;
        
        private final String fileDirectory;

        // pour les cas où tu veux parser plus loin
        String sender;
        int expectedLength = -1;

        PrivateContext(SocketChannel sc, long expectedToken, String fileDirectory) {
        	if (fileDirectory == null || fileDirectory.isBlank()) {
                throw new IllegalArgumentException("fileDirectory ne doit pas être null ou vide");
            }
            this.sc = sc;
            this.expectedToken = expectedToken;
            this.fileDirectory = fileDirectory;
        }

        boolean processRead() throws IOException {
            int read = sc.read(bufferIn);
            if (read == -1) {
                return false;
            }
            bufferIn.flip();
            while (bufferIn.hasRemaining()) {
                switch (state) {
	                case WAITING_OPCODE -> {
	                    if (bufferIn.remaining() < 1) {
	                        bufferIn.compact();
	                        return true;
	                    }
	                    currentOpcode = bufferIn.get();
	                    if (currentOpcode == OPCODE.OPEN.getCode()) {
	                        state = State.WAITING_OPEN;
	                    } else if (currentOpcode == OPCODE.MESSAGE.getCode()) {
	                        state = State.WAITING_MESSAGE;
	                    } else if (currentOpcode == OPCODE.FILE.getCode()) {
	                        state = State.WAITING_FILE;
	                    } else {
	                        System.out.println("Opcode inconnu en privé : " + currentOpcode);
	                        return false;
	                    }
	                }
                    case WAITING_OPEN -> {
                        if (bufferIn.remaining() < Long.BYTES) {
                            bufferIn.compact();
                            return true;
                        }
                        long tokenReceived = bufferIn.getLong();
                        if (tokenReceived == expectedToken) {
                            System.out.println("Connexion privée confirmée !");
                            activePrivateContext = this;
                            privateSessionActive = true;
                        } else {
                            System.out.println("Connexion privée refusée (token invalide).");
                            return false;
                        }
                        state = State.WAITING_OPCODE;
                    }


                    case WAITING_MESSAGE -> {
                        if (bufferIn.remaining() < Integer.BYTES) {
                            bufferIn.compact();
                            return true;
                        }
                        int senderLen = bufferIn.getInt();
                        if (bufferIn.remaining() < senderLen + Integer.BYTES) {
                            bufferIn.position(bufferIn.position() - Integer.BYTES);
                            bufferIn.compact();
                            return true;
                        }
                        byte[] senderBytes = new byte[senderLen];
                        bufferIn.get(senderBytes);
                        sender = UTF8.decode(ByteBuffer.wrap(senderBytes)).toString();

                        int msgLen = bufferIn.getInt();
                        if (bufferIn.remaining() < msgLen) {
                            bufferIn.position(bufferIn.position() - Integer.BYTES - senderLen);
                            bufferIn.compact();
                            return true;
                        }
                        byte[] msgBytes = new byte[msgLen];
                        bufferIn.get(msgBytes);
                        String msg = UTF8.decode(ByteBuffer.wrap(msgBytes)).toString();

                        System.out.println(sender + " (privé) : " + msg);
                        state = State.WAITING_OPCODE;
                    }
                    case WAITING_FILE -> {
                        if (bufferIn.remaining() < Integer.BYTES) {
                            bufferIn.compact();
                            return true;
                        }

                        int filenameLen = bufferIn.getInt();
                        if (bufferIn.remaining() < filenameLen + Integer.BYTES * 2) {
                            bufferIn.position(bufferIn.position() - Integer.BYTES);
                            bufferIn.compact();
                            return true;
                        }

                        byte[] filenameBytes = new byte[filenameLen];
                        bufferIn.get(filenameBytes);
                        String filename = UTF8.decode(ByteBuffer.wrap(filenameBytes)).toString();

                        int totalSize = bufferIn.getInt();
                        int chunkSize = bufferIn.getInt();

                        if (bufferIn.remaining() < chunkSize) {
                            bufferIn.position(bufferIn.position() - filenameLen - Integer.BYTES * 2);
                            bufferIn.compact();
                            return true;
                        }

                        byte[] chunk = new byte[chunkSize];
                        bufferIn.get(chunk);

                        if (!filename.equals(currentFileName)) {
                            if (currentFileOut != null) {
                                currentFileOut.close();
                            }
                            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                                System.err.println("Nom de fichier invalide reçu : " + filename);
                                return false;
                            }
                            System.out.println("Réception d’un fichier nommé : " + filename);
                            System.out.println("Chemin complet : " + fileDirectory + "/" + filename);

                            if (filename == null || filename.isBlank() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                                System.err.println("Nom de fichier invalide : " + filename);
                                return false;
                            }

                            File dir = new File(fileDirectory);
                            if (!dir.exists() || !dir.isDirectory()) {
                                System.err.println("Dossier introuvable ou invalide : " + fileDirectory);
                                return false;
                            }

                            File f = new File(fileDirectory, filename);
                            currentFileOut = new FileOutputStream(f);
                            currentFileName = filename;
                            expectedFileSize = totalSize;
                            receivedBytes = 0;
                        }

                        currentFileOut.write(chunk);
                        receivedBytes += chunkSize;

                        System.out.println("Chunk reçu (" + chunkSize + " octets) pour : " + filename);

                        if (receivedBytes >= expectedFileSize) {
                            currentFileOut.close();
                            System.out.println("Fichier terminé et sauvegardé : " + filename);
                            currentFileName = null;
                            currentFileOut = null;
                        }

                        state = State.WAITING_OPCODE;
                    }
                }
            }
            bufferIn.compact();
            return true;
        }
    }

   private static void doPrivateAccept(SelectionKey key) throws IOException {
	   SocketChannel scPrivate = privateServer.accept();
	   if (scPrivate != null) {
	       logger.info("Nouvelle connexion privée entrante acceptée.");
	       scPrivate.configureBlocking(false);
	       
	       SelectionKey clientKey = scPrivate.register(privateSelector, SelectionKey.OP_READ);
	       PrivateContext context = new PrivateContext(scPrivate, expectedIncomingToken, fileDirectory);
	       privateContexts.put(clientKey, context);

	       privateSocket = scPrivate;
	       activePrivateContext = context;
	       privateSessionActive = true;
	   }
	}



    private static void doPrivateRead(SelectionKey key) throws IOException {
        PrivateContext ctx = privateContexts.get(key);
        if (ctx == null) return;

        boolean ok = ctx.processRead();
        if (!ok) {
            silentlyClosePrivate(key);
        }
    }

    private static void doPrivateWrite(SelectionKey key) throws IOException {
        // no-op pour maintenant, sauf si tu veux envoyer un message plus tard depuis ici
    }
    
    public static void handlePrivateSelectorEvents() throws IOException {
        if (privateSelector.selectNow() > 0) {
            var iter = privateSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
                var key = iter.next();
                iter.remove();

                if (key.isValid() && key.isAcceptable()) {
                    doPrivateAccept(key);
                }
                if (key.isValid() && key.isReadable()) {
                    doPrivateRead(key);
                }
                if (key.isValid() && key.isWritable()) {
                    doPrivateWrite(key);
                }
            }
        }
    }

    private static void silentlyClosePrivate(SelectionKey key) {
        try {
            logger.info("Fermeture silencieuse de la connexion privée.");
            key.channel().close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Erreur lors de la fermeture de la connexion privée", e);
        }
        privateContexts.remove(key);
    }

    private static void sendMessage(int version, SocketChannel sc, byte opcode, long id, String message, String peusdo, String target_peusdo) throws IOException {
        ByteBuffer encodedMessage = null;
        var encodedPeusdo = StandardCharsets.UTF_8.encode(peusdo);

        InetAddress inetAddress = sc.socket().getLocalAddress();
        byte[] ipBytes = inetAddress.getAddress();
        int port = (opcode == OPCODE.OK_PRIVATE.getCode()) ? privatePort : sc.socket().getLocalPort();

        if (!message.isEmpty()) {
            encodedMessage = StandardCharsets.UTF_8.encode(message);
        }

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(opcode);
        bb.putInt(encodedPeusdo.remaining());
        bb.put(encodedPeusdo);

        if (opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.OK_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
            var encodedTargetPeusdo = StandardCharsets.UTF_8.encode(target_peusdo);
            bb.putInt(encodedTargetPeusdo.remaining());
            bb.put(encodedTargetPeusdo);
        }

        if (opcode == OPCODE.OK_PRIVATE.getCode()) {
            bb.put((byte) version);
            bb.put(ipBytes);
            bb.putInt(port);
            bb.putLong(id);
        }

        if (encodedMessage != null) {
            bb.putInt(encodedMessage.remaining());
            bb.put(encodedMessage);
        }

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }

    private static void startReceiver(SocketChannel sc) {
        new Thread(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 8);
            try {
                while (true) {
                    buffer.clear();
                    int read = sc.read(buffer);
                    if (read == -1) {
                        System.out.println("Serveur a fermé la connexion.");
                        break;
                    }
                    buffer.flip();
                    if (buffer.hasRemaining()) {
                        decodeMessage(buffer);
                    }
                }
            } catch (IOException e) {
                System.err.println("Erreur de lecture : " + e.getMessage());
            }
        }).start();
    }

    private static void decodeMessage(ByteBuffer buffer) throws IOException {
        if (!buffer.hasRemaining()) {
            System.out.println("Message vide.");
            return;
        }

        byte rawOpcode = buffer.get();
        OPCODE op = OPCODE.fromCode(rawOpcode);
        if (op == null) {
            System.out.printf("Opcode inconnu : %02X\n", rawOpcode);
            return;
        }
        
        switch (op) {
            case LOGIN_ACCEPTED -> System.out.println("Connexion acceptée.");
            case LOGIN_REFUSED -> System.out.println("Connexion refusée. Vérifiez votre pseudonyme ou mot de passe.");
            case LOGINAUTH -> System.out.println("Authentification réussie.");
            case REQUEST_PRIVATE -> {
                if (buffer.remaining() < Integer.BYTES) return;
                int senderLength = buffer.getInt();
                if (buffer.remaining() < senderLength + Integer.BYTES) return;

                byte[] senderBytes = new byte[senderLength];
                buffer.get(senderBytes);
                String sender = UTF8.decode(ByteBuffer.wrap(senderBytes)).toString();

                int targetLength = buffer.getInt();
                if (buffer.remaining() < targetLength) return;

                byte[] targetBytes = new byte[targetLength];
                buffer.get(targetBytes);
                String target = UTF8.decode(ByteBuffer.wrap(targetBytes)).toString();

                pendingPrivateRequest = new PrivateRequest(sender, target);   
                if (privateTargetPeusdo != null && sender.equals(privateTargetPeusdo)) {
                    System.out.println("Connexion privée déjà en cours avec " + sender + ", pas de redemande nécessaire.");
                    return;
                }
            }
            case OK_PRIVATE -> {
                System.out.println("La connexion privée a été acceptée par le destinataire.");

                // 1. Lire login_requester
                int requesterLen = buffer.getInt();
                byte[] requesterBytes = new byte[requesterLen];
                buffer.get(requesterBytes);
                String requester = UTF8.decode(ByteBuffer.wrap(requesterBytes)).toString();

                // 2. Lire login_target
                int targetLen = buffer.getInt();
                byte[] targetBytes = new byte[targetLen];
                buffer.get(targetBytes);
                String target = UTF8.decode(ByteBuffer.wrap(targetBytes)).toString();

                // 3. Lire ip_type + ip address
                byte ipType = buffer.get();
                byte[] ipBytes = new byte[ipType == 0x04 ? 4 : 16];
                buffer.get(ipBytes);
                InetAddress ipAddress = InetAddress.getByAddress(ipBytes);

                // 4. Lire port
                int port = buffer.getInt();

                // 5. Lire token
                long token = buffer.getLong();

                // Si je suis le target (et donc je n’ai pas ouvert de socket moi-même)
                if (!hasOpenedPrivateSocket && requester != null && !requester.equals(peusdo)) {
                    System.out.println("Connexion inversée : ouverture d'une socket vers " + requester);

                    privateSocketAddress = new InetSocketAddress(ipAddress, port);
                    privateToken = token;

                    privateSocket = SocketChannel.open();
                    privateSocket.connect(privateSocketAddress);
                    privateSocket.configureBlocking(false);

                    SelectionKey privateKey = privateSocket.register(privateSelector, SelectionKey.OP_READ);
                    PrivateContext context = new PrivateContext(privateSocket, token, fileDirectory);
                    privateContexts.put(privateKey, context);
                    activePrivateContext = context;

                    ByteBuffer openMsg = ByteBuffer.allocate(9);
                    openMsg.put(OPCODE.OPEN.getCode());
                    openMsg.putLong(token);
                    openMsg.flip();
                    privateSocket.write(openMsg);

                    privateSessionActive = true;
                    privateTargetPeusdo = requester;
                    hasOpenedPrivateSocket = true;
                }

                // dans tous les cas on enregistre le target pour savoir à qui parler
                privateTargetPeusdo = target;
            }
            case KO_PRIVATE -> System.out.println("La connexion privée a été refusée par le destinataire.");
            case CONNECTED_USERS_LIST -> {
                if (buffer.remaining() < Integer.BYTES) {
                    System.out.println("Erreur : données insuffisantes pour la taille de la liste.");
                    return;
                }

                int listLength = buffer.getInt();
                if (buffer.remaining() < listLength) {
                    System.out.println("Erreur : données incomplètes pour la liste des utilisateurs.");
                    return;
                }

                byte[] listBytes = new byte[listLength];
                buffer.get(listBytes);
                String userList = UTF8.decode(ByteBuffer.wrap(listBytes)).toString();
                System.out.println("Utilisateurs connectés :\n" + userList);
            }
            case MESSAGE -> {
                if (buffer.remaining() < Integer.BYTES) return;
                int senderLength = buffer.getInt();
                if (buffer.remaining() < senderLength + Integer.BYTES) return;

                byte[] senderBytes = new byte[senderLength];
                buffer.get(senderBytes);
                String sender = UTF8.decode(ByteBuffer.wrap(senderBytes)).toString();

                int messageLength = buffer.getInt();
                if (buffer.remaining() < messageLength) return;

                byte[] msgBytes = new byte[messageLength];
                buffer.get(msgBytes);
                String message = UTF8.decode(ByteBuffer.wrap(msgBytes)).toString();

                System.out.println(sender + " : " + message);
            }
            default -> System.out.println("Réponse : " + op);
        }
    }

    private static void login(SocketChannel sc, long id, String username, String password) throws IOException, InterruptedException {
        String credentials = username;
        peusdo = username;
        byte opcodeToSend = password.isEmpty() ? OPCODE.LOGIN.getCode() : OPCODE.LOGINAUTH.getCode();

        sendMessage(4, sc, opcodeToSend, id, "", peusdo, null);
        System.out.println("Tentative de connexion...");
        Thread.sleep(200);

        sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo, null);
    }
    
    private static SocketChannel getPrivateChannel() {
        return activePrivateContext != null ? activePrivateContext.sc : null;
    }
    
    private static void sendFile(SocketChannel sc, String filename, File file) throws IOException {
        isSendingFile = true;

        byte[] fileNameBytes = UTF8.encode(filename).array();
        int totalSize = (int) file.length();

        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                ByteBuffer bb = ByteBuffer.allocate(1 + 4 + fileNameBytes.length + 4 + 4 + bytesRead);

                bb.put(OPCODE.FILE.getCode());
                bb.putInt(fileNameBytes.length);
                bb.put(fileNameBytes);
                bb.putInt(totalSize);
                bb.putInt(bytesRead);
                bb.put(buffer, 0, bytesRead);

                bb.flip();
                while (bb.hasRemaining()) {
                    sc.write(bb);
                }
            }
            System.out.println("Fichier envoyé : " + filename);
        } catch (IOException e) {
            System.err.println("Erreur pendant l'envoi du fichier : " + e.getMessage());
        }

        isSendingFile = false;
    }
    
    void closeFileStream() {
        try {
            if (currentFileOut != null) {
                currentFileOut.close();
                System.out.println("Flux fichier fermé.");
            }
        } catch (IOException e) {
            System.err.println("Erreur fermeture flux fichier : " + e.getMessage());
        }
        currentFileOut = null;
        currentFileName = null;
        expectedFileSize = 0;
        receivedBytes = 0;
    }


    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java ClientTest <pseudonyme> <mot_de_passe> <dossier_fichier>");
            return;
        }

        String username = args[0];
        String password = args[1];
        fileDirectory = args[2];

        File dir = new File(fileDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("Erreur: Le dossier spécifié n'existe pas ou n'est pas un dossier.");
            return;
        }

        try (SocketChannel sc = SocketChannel.open()) {
            MainSc = sc;
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
            System.out.println("Connexion établie avec le serveur.");
            
            if (privateServer == null || !privateServer.isOpen()) {
                privateSelector = Selector.open();
                privateServer = ServerSocketChannel.open();
                privateServer.configureBlocking(false);
                privateServer.bind(new InetSocketAddress(0));
                privateServer.register(privateSelector, SelectionKey.OP_ACCEPT);
                privatePort = ((InetSocketAddress) privateServer.getLocalAddress()).getPort();
                logger.info("Serveur privé lancé sur le port : " + privatePort);
            }
            

            startReceiver(sc);

            long id = System.currentTimeMillis();

            login(sc, id, username, password);

            while (true) {
                if (privateSelector.selectNow() > 0) {
                    var iter = privateSelector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        var key = iter.next();
                        iter.remove();

                        try {
                            if (key.isValid() && key.isAcceptable()) {
                                doPrivateAccept(key);
                            }
                            if (key.isValid() && key.isReadable()) {
                                doPrivateRead(key);
                            }
                            if (key.isValid() && key.isWritable()) {
                                doPrivateWrite(key); // utile si tu veux envoyer plus tard
                            }
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Erreur lors du traitement d'une clé privée", e);
                            silentlyClosePrivate(key);
                        }
                    }
                }

                System.out.print("> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (pendingPrivateRequest != null) {
                    var request = pendingPrivateRequest;
                    pendingPrivateRequest = null;

                    System.out.print("Demande de connexion privée reçue de : " + request.peusdoRequester() + ". Accepter ? (o/n) > ");
                    String reply = scanner.nextLine().trim().toLowerCase();

                    if (reply.equals("o") || reply.equals("oui")) {
                    	privateToken = System.currentTimeMillis();
                    	expectedIncomingToken = privateToken;
                    	sendMessage(4, MainSc, OPCODE.OK_PRIVATE.getCode(), privateToken, "", peusdo, request.peusdoRequester());
                        System.out.println("Connexion privée acceptée avec " + request.peusdoRequester());
                        privateTargetPeusdo = request.peusdoRequester();
                    } else {
                        sendMessage(4, MainSc, OPCODE.KO_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo, request.peusdoRequester());
                        System.out.println("Connexion privée refusée.");
                    }
                    continue;
                }

                if (line.equalsIgnoreCase("/getusers")) {
                    sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), System.currentTimeMillis(), "", peusdo, null);
                    continue;
                }

                if (line.startsWith("@")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : @login");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    if (login.equals(peusdo)) {
                        System.out.println("Impossible d'avoir une connexion privée avec soi-même");
                        continue;
                    }
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo, login);
                    hasInitiatedPrivateRequest = true;
                } else if (line.startsWith("/")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : /<login> <nom_du_fichier>");
                        continue;
                    }

                    String targetLogin = line.substring(1, spaceIndex).trim();
                    String filename = line.substring(spaceIndex + 1).trim();

                    if (privateTargetPeusdo == null || !targetLogin.equalsIgnoreCase(privateTargetPeusdo)) {
                        System.out.println("Vous n'avez pas de session privée avec " + targetLogin);
                        continue;
                    }

                    File file = new File(fileDirectory, filename);
                    if (!file.exists() || !file.isFile()) {
                        System.out.println("Fichier introuvable : " + filename);
                        continue;
                    }

                    sendFile(getPrivateChannel(), filename, file);
                    continue;
                } else {
                	SocketChannel targetChannel = privateSessionActive && getPrivateChannel() != null
                		    ? getPrivateChannel()
                		    : MainSc;
                    sendMessage(4, targetChannel, OPCODE.MESSAGE.getCode(), System.currentTimeMillis(), line, peusdo, null);
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Erreur client : " + e.getMessage());
        }
    }
}
