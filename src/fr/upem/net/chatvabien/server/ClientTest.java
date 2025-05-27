package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.OPCODE;
import fr.upem.net.chatvabien.protocol.PrivateRequest;
import fr.upem.net.chatvabien.protocol.User;
import fr.upem.net.chatvabien.protocol.Reader.ProcessStatus;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientTest {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static final Charset UTF8 = Charset.forName("UTF8");
    private static final int MAX_BUFFER_SIZE = 1024;
	private static final Logger logger = Logger.getLogger(ClientTest.class.getName());
    private String peusdo;
    private String fileDirectory;
    private SocketChannel MainSc;
    private boolean isPrivateResquested = false;
    private PrivateRequest pendingPrivateRequest = null;
    private SocketChannel privateSocket = null;
    private InetSocketAddress privateSocketAddress = null;
    private long privateToken = -1;
    private boolean privateSessionActive = false;
    private int privatePort = -1;
    private ServerSocketChannel privateServer = null;
    private Selector privateSelector = null;
    private long expectedIncomingToken = -1;
    private PrivateContext activePrivateContext = null;
    private  boolean isSendingFile = false;
    FileOutputStream currentFileOut = null;
    String currentFileName = null;
    int expectedFileSize = 0;
    int receivedBytes = 0;
    private static String privateTargetPeusdo = null;
    private boolean hasInitiatedPrivateRequest = false;
    private boolean hasOpenedPrivateSocket = false;
    private static Selector mainSelector;
    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);
    private PrivateRequest pendingPrompt = null; // requête en attente d'une réponse utilisateur
    private boolean waitingForPrivateReply = false;
    
    
    private static final Scanner scanner = new Scanner(System.in);
    private static final Map<SelectionKey, PrivateContext> privateContexts = new HashMap<>();
    
   private class PrivateContext {
        final SocketChannel sc;
        final ByteBuffer bufferIn = ByteBuffer.allocate(1024);
        private final LongReader longReader = new LongReader();
        private final StringReader senderReader = new StringReader();
        private final StringReader messageReader = new StringReader();

        enum State { WAITING_OPCODE, WAITING_OPEN, WAITING_MESSAGE, WAITING_FILE }
        State state = State.WAITING_OPCODE;

        OPCODE currentOpcode;
        long expectedToken;
        
        private final String fileDirectory;

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
                        currentOpcode = OPCODE.fromCode(bufferIn.get());
                        switch (currentOpcode) {
                            case OPEN -> state = State.WAITING_OPEN;
                            case MESSAGE -> state = State.WAITING_MESSAGE;
                            case FILE -> state = State.WAITING_FILE;
                            default -> {
                                System.out.println("Opcode inconnu en privé : " + currentOpcode);
                                return false;
                            }
                        }
                    }

                    case WAITING_OPEN -> {
                        ProcessStatus status = longReader.process(bufferIn);
                        if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return true;
                        }
                        if (status == ProcessStatus.ERROR) {
                            System.out.println("Erreur lors de la lecture du token.");
                            return false;
                        }
                        long tokenReceived = longReader.get();
                        longReader.reset();
                        if (tokenReceived != expectedToken) {
                            System.out.println("Connexion privée refusée (token invalide).");
                            return false;
                        }
                        System.out.println("Connexion privée confirmée !");
                        activePrivateContext = this;
                        privateSessionActive = true;
                        state = State.WAITING_OPCODE;
                    }

                    case WAITING_MESSAGE -> {
                        var status = senderReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            sender = senderReader.get();
                            senderReader.reset();
                            state = State.WAITING_MESSAGE; // continue to message reading
                        } else {
                            bufferIn.compact();
                            return true;
                        }

                        status = messageReader.process(bufferIn);
                        if (status == ProcessStatus.DONE) {
                            String message = messageReader.get();
                            messageReader.reset();
                            System.out.println(sender + " (privé) : " + message);
                            state = State.WAITING_OPCODE;
                        } else {
                            bufferIn.compact();
                        }
                        return true;
                    }

                    case WAITING_FILE -> {
                        // Vérifie si on peut lire la longueur du nom de fichier
                        if (bufferIn.remaining() < Integer.BYTES) {
                            bufferIn.compact();
                            return true;
                        }

                        // Lit la longueur du nom de fichier sans reset()
                        int filenameLen = bufferIn.get();
                        if (bufferIn.remaining() < filenameLen + Integer.BYTES * 2) {
                            bufferIn.position(bufferIn.position() - 1); // annule la lecture de filenameLen
                            bufferIn.compact();
                            return true;
                        }

                        // Lecture du nom de fichier
                        ByteBuffer filenameBuf = ByteBuffer.allocate(filenameLen);
                        for (int i = 0; i < filenameLen; i++) {
                            filenameBuf.put(bufferIn.get());
                        }
                        filenameBuf.flip();
                        String filename = UTF8.decode(filenameBuf).toString();

                        // Lecture taille totale et taille du chunk
                        if (bufferIn.remaining() < Integer.BYTES * 2) {
                            // rollback: on replace le buffer à l’état avant filenameLen
                            bufferIn.position(bufferIn.position() - filenameLen - 1);
                            bufferIn.compact();
                            return true;
                        }

                        int totalSize = bufferIn.getInt();
                        int chunkSize = bufferIn.getInt();

                        if (bufferIn.remaining() < chunkSize) {
                            bufferIn.position(bufferIn.position() - filenameLen - 1 - Integer.BYTES * 2);
                            bufferIn.compact();
                            return true;
                        }

                        // Prépare chunk direct
                        ByteBuffer chunk = bufferIn.slice();
                        chunk.limit(chunkSize);
                        bufferIn.position(bufferIn.position() + chunkSize);

                        // Nouveau fichier ?
                        if (!filename.equals(currentFileName)) {
                            if (currentFileOut != null) currentFileOut.close();
                            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                                System.err.println("Nom de fichier invalide reçu : " + filename);
                                return false;
                            }

                            File dir = new File(fileDirectory);
                            if (!dir.exists() || !dir.isDirectory()) {
                                System.err.println("Dossier invalide : " + fileDirectory);
                                return false;
                            }

                            File f = new File(fileDirectory, filename);
                            currentFileOut = new FileOutputStream(f);
                            currentFileName = filename;
                            expectedFileSize = totalSize;
                            receivedBytes = 0;

                            System.out.println("Réception d’un fichier nommé : " + filename);
                            System.out.println("Chemin complet : " + fileDirectory + "/" + filename);
                        }

                        while (chunk.hasRemaining()) {
                            currentFileOut.getChannel().write(chunk);
                        }

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

   private void doPrivateAccept(SelectionKey key) throws IOException {
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



    private void doPrivateRead(SelectionKey key) throws IOException {
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
    
    public void handlePrivateSelectorEvents() throws IOException {
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

    
    
    private void sendLoginMessage(SocketChannel sc, byte opcode, long id, String peusdo) throws IOException {
        ByteBuffer pseudoBuf = UTF8.encode(peusdo);
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(opcode);
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }
    
    private void sendRequestPrivateMessage(SocketChannel sc, String peusdo, String target) throws IOException {
        ByteBuffer pseudoBuf = UTF8.encode(peusdo);
        ByteBuffer targetBuf = UTF8.encode(target);

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(OPCODE.REQUEST_PRIVATE.getCode());
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);
        bb.putInt(targetBuf.remaining());
        bb.put(targetBuf);

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }
    
    private void sendOKPrivateMessage(SocketChannel sc, int version, long token, String peusdo, String target, SocketChannel ip, int port) throws IOException {
        ByteBuffer pseudoBuf = UTF8.encode(peusdo);
        ByteBuffer targetBuf = UTF8.encode(target);
        InetAddress inetAddress = ip.getLocalAddress();
        byte[] ipBytes = inetAddress.getAddress(); // te donne directement la bonne taille
        byte ipType = (byte) ipBytes.length;

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(OPCODE.OK_PRIVATE.getCode());
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);
        bb.putInt(targetBuf.remaining());
        bb.put(targetBuf);
        bb.put((byte) version);
        bb.put(ipBuf);
        bb.putInt(port);
        bb.putLong(token);

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }

    private void sendKOPrivateMessage(SocketChannel sc, String peusdo, String target) throws IOException {
        ByteBuffer pseudoBuf = UTF8.encode(peusdo);
        ByteBuffer targetBuf = UTF8.encode(target);

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(OPCODE.KO_PRIVATE.getCode());
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);
        bb.putInt(targetBuf.remaining());
        bb.put(targetBuf);

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }

    private void sendTextMessage(SocketChannel sc, String peusdo, String message) throws IOException {
        ByteBuffer pseudoBuf = UTF8.encode(peusdo);
        ByteBuffer msgBuf = UTF8.encode(message);

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(OPCODE.MESSAGE.getCode());
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);
        bb.putInt(msgBuf.remaining());
        bb.put(msgBuf);

        bb.flip();
        while (bb.hasRemaining()) {
            sc.write(bb);
        }
    }

    

    private void decodeMessage(ByteBuffer buffer) throws IOException {
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

                byte ipType = buffer.get();
                int ipLength = switch (ipType) {
                    case 0x04 -> 4;
                    case 0x06, 0x10 -> 16;
                    default -> throw new IllegalArgumentException("Type IP inconnu: " + ipType);
                };
                byte[] ipBytes = new byte[ipLength];
                buffer.get(ipBytes);
                InetAddress ipAddress = InetAddress.getByAddress(ipBytes);

                int port = buffer.getInt();
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

    private void login(SocketChannel sc, long id, String username, String password) throws IOException, InterruptedException {
        String credentials = username;
        peusdo = username;
        byte opcodeToSend = password.isEmpty() ? OPCODE.LOGIN.getCode() : OPCODE.LOGINAUTH.getCode();

        sendLoginMessage(sc, opcodeToSend, id, peusdo);
        System.out.println("Tentative de connexion...");
        sendLoginMessage(sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, peusdo);
    }
    
    private SocketChannel getPrivateChannel() {
        return activePrivateContext != null ? activePrivateContext.sc : null;
    }
    
    private  void sendFile(SocketChannel sc, String filename, File file) throws IOException {
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
    
    private void doMainRead(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 8); // alloué à chaque lecture (ou à réutiliser)
        int read = sc.read(buffer);
        if (read == -1) {
            System.out.println("Serveur a fermé la connexion.");
            sc.close();
            return;
        }
        buffer.flip();
        if (buffer.hasRemaining()) {
            decodeMessage(buffer);
        }
    }
    
    private void handleUserCommand(String line) throws IOException {
        if (line == null || line.isBlank()) return;

        if (waitingForPrivateReply) {
            String reply = line.trim().toLowerCase();
            if (pendingPrompt != null) {
                if (reply.equals("o") || reply.equals("oui")) {
                    privateToken = System.currentTimeMillis();
                    expectedIncomingToken = privateToken;
                    sendOKPrivateMessage(MainSc, 4, privateToken, peusdo, pendingPrompt.peusdoRequester(), MainSc, SERVER_PORT);
                    System.out.println("Connexion privée acceptée avec " + pendingPrompt.peusdoRequester());
                    privateTargetPeusdo = pendingPrompt.peusdoRequester();
                } else {
                    sendKOPrivateMessage(MainSc, peusdo, pendingPrompt.peusdoRequester());
                    System.out.println("Connexion privée refusée.");
                }
            }
            pendingPrompt = null;
            waitingForPrivateReply = false;
            return;
        }

        if (pendingPrivateRequest != null) {
            pendingPrompt = pendingPrivateRequest;
            pendingPrivateRequest = null;
            waitingForPrivateReply = true;
            System.out.println("Demande de connexion privée reçue de : " + pendingPrompt.peusdoRequester() + ". Accepter ? (o/n) > ");
            return;
        }

        if (line.equalsIgnoreCase("/getusers")) {
            sendLoginMessage(MainSc, OPCODE.GET_CONNECTED_USERS.getCode(), System.currentTimeMillis(), peusdo);
            return;
        }

        if (line.startsWith("@")) {
            int spaceIndex = line.indexOf(' ');
            if (spaceIndex == -1 || spaceIndex == 1) {
                System.out.println("Syntaxe invalide. Utilisez : @login");
                return;
            }
            String login = line.substring(1, spaceIndex);
            if (login.equals(peusdo)) {
                System.out.println("Impossible d'avoir une connexion privée avec soi-même");
                return;
            }
            sendRequestPrivateMessage(MainSc, peusdo, login);
            hasInitiatedPrivateRequest = true;
            return;
        }

        if (line.startsWith("/")) {
            int spaceIndex = line.indexOf(' ');
            if (spaceIndex == -1 || spaceIndex == 1) {
                System.out.println("Syntaxe invalide. Utilisez : /<login> <nom_du_fichier>");
                return;
            }

            String targetLogin = line.substring(1, spaceIndex).trim();
            String filename = line.substring(spaceIndex + 1).trim();

            if (privateTargetPeusdo == null || !targetLogin.equalsIgnoreCase(privateTargetPeusdo)) {
                System.out.println("Vous n'avez pas de session privée avec " + targetLogin);
                return;
            }

            File file = new File(fileDirectory, filename);
            if (!file.exists() || !file.isFile()) {
                System.out.println("Fichier introuvable : " + filename);
                return;
            }

            sendFile(getPrivateChannel(), filename, file);
            return;
        }

        // Message texte normal (public ou privé actif)
        SocketChannel targetChannel = privateSessionActive && getPrivateChannel() != null
                ? getPrivateChannel()
                : MainSc;
        sendTextMessage(targetChannel, peusdo, line);
    }


    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java ClientTest <pseudonyme> <mot_de_passe> <dossier_fichier>");
            return;
        }
        
        ClientTest client = new ClientTest();
        client.mainLoop(args[0], args[1], args[2]);
    }

    private void mainLoop(String username, String password, String fileDirectory) {

        File dir = new File(fileDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("Erreur: Le dossier spécifié n'existe pas ou n'est pas un dossier.");
            return;
        }
        try (SocketChannel sc = SocketChannel.open()) {
            MainSc = sc;
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
            mainSelector = Selector.open();
            MainSc.configureBlocking(false);
            MainSc.register(mainSelector, SelectionKey.OP_READ);
            System.out.println("Lien établie avec le serveur.");
            
            if (privateServer == null || !privateServer.isOpen()) {
                privateSelector = Selector.open();
                privateServer = ServerSocketChannel.open();
                privateServer.configureBlocking(false);
                privateServer.bind(new InetSocketAddress(0));
                privateServer.register(privateSelector, SelectionKey.OP_ACCEPT);
                privatePort = ((InetSocketAddress) privateServer.getLocalAddress()).getPort();
                logger.info("Serveur privé lancé sur le port : " + privatePort);
            }
            


            long id = System.currentTimeMillis();

            login(sc, id, username, password);
            
            new Thread(() -> {
                while (true) {
                    System.out.print("> ");
                    String line = scanner.nextLine().trim();
                    try {
                        commandQueue.put(line);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
            

            while (true) {
            	if (mainSelector.selectNow() > 0) {
            	    var iter = mainSelector.selectedKeys().iterator();
            	    while (iter.hasNext()) {
            	        var key = iter.next();
            	        iter.remove();

            	        if (key.isValid() && key.isReadable()) {
            	            doMainRead(key);
            	        }
            	    }
            	}
                if (privateSelector.selectNow() > 0) {
                    var iter = privateSelector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        var key = iter.next();
                        iter.remove();

                        try {
                        	if (key.isValid() && key.channel() == MainSc && key.isReadable()) {
                        	    doMainRead(key);
                        	}
                            if (key.isValid() && key.isAcceptable()) {
                                doPrivateAccept(key);
                            }
                            if (key.isValid() && key.isReadable()) {
                                doPrivateRead(key);
                            }
                            if (key.isValid() && key.isWritable()) {
                                doPrivateWrite(key);
                            }
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Erreur lors du traitement d'une clé privée", e);
                            silentlyClosePrivate(key);
                        }
                    }
                }
                
                String command = commandQueue.poll();
                if (command != null) {
                    handleUserCommand(command);
                }
                
                Thread.sleep(10);
            }

        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Erreur client : " + e.getMessage());
        }
    }
}
