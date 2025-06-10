package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.LongReader;
import fr.upem.net.chatvabien.protocol.StringReader;
import fr.upem.net.chatvabien.protocol.OPCODE;
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
import java.util.ArrayList;
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
    private PrivateRequestSimple pendingPrivateRequest = null;
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
    private PrivateRequestSimple pendingPrompt = null;
    private boolean waitingForPrivateReply = false;
    private String userList = "";
    private boolean shouldDisplayUserList = false;
    
    
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
                                                
                        privateSessionActive = true;
                        state = State.WAITING_OPCODE;
                    }

                    case WAITING_MESSAGE -> {
                        var status = senderReader.process(bufferIn);
                        if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return true;
                        }
                        if (status == ProcessStatus.ERROR) {
                            System.out.println("Erreur lors de la lecture du sender.");
                            return false;
                        }
                        if (status == ProcessStatus.DONE) {
                            sender = senderReader.get();
                            senderReader.reset();
                        } else {
                            bufferIn.compact();
                            return true;
                        }

                        status = messageReader.process(bufferIn);
                        if (status == ProcessStatus.REFILL) {
                            bufferIn.compact();
                            return true;
                        }
                        if (status == ProcessStatus.ERROR) {
                            System.out.println("Erreur lors de la lecture du message.");
                            return false;
                        }
                        if (status == ProcessStatus.DONE) {
                            String message = messageReader.get();
                            messageReader.reset();
                            System.out.println(sender + " (privé) : " + message);
                            state = State.WAITING_OPCODE;
                        } else {
                            bufferIn.compact();
                            return true;
                        }
                    }

                    case WAITING_FILE -> {
                        if (bufferIn.remaining() < 1) {
                            bufferIn.compact();
                            return true;
                        }

                        int filenameLen = bufferIn.get() & 0xFF;
                        if (bufferIn.remaining() < filenameLen + Integer.BYTES * 2) {
                            bufferIn.position(bufferIn.position() - 1);
                            bufferIn.compact();
                            return true;
                        }

                        ByteBuffer filenameBuffer = ByteBuffer.allocate(filenameLen);
                        for (int i = 0; i < filenameLen; i++) {
                            filenameBuffer.put(bufferIn.get());
                        }
                        filenameBuffer.flip();
                        String filename = UTF8.decode(filenameBuffer).toString();

                        if (bufferIn.remaining() < Integer.BYTES * 2) {
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

                        ByteBuffer chunkBuffer = ByteBuffer.allocate(chunkSize);
                        for (int i = 0; i < chunkSize; i++) {
                            chunkBuffer.put(bufferIn.get());
                        }
                        chunkBuffer.flip();

                        if (!filename.equals(currentFileName)) {
                            if (currentFileOut != null) {
                                currentFileOut.close();
                            }
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

                            System.out.println("Réception d'un fichier nommé : " + filename + " (" + totalSize + " octets)");
                        }

                        byte[] chunkData = new byte[chunkBuffer.remaining()];
                        chunkBuffer.get(chunkData);
                        currentFileOut.write(chunkData);
                        receivedBytes += chunkSize;
                        
                        System.out.println("Chunk reçu (" + chunkSize + " octets) - Progression : " + receivedBytes + "/" + expectedFileSize);

                        if (receivedBytes >= expectedFileSize) {
                            currentFileOut.close();
                            System.out.println("Fichier terminé et sauvegardé : " + filename);
                            currentFileName = null;
                            currentFileOut = null;
                            receivedBytes = 0;
                            expectedFileSize = 0;
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

	        if (activePrivateContext == null) {
	            activePrivateContext = context;
	            privateSocket = scPrivate;
	            privateSessionActive = true;
	        }
	    }
	}
   
   private boolean isPrivateSessionActive() {
	    return activePrivateContext != null && 
	           activePrivateContext.sc != null && 
	           activePrivateContext.sc.isConnected() &&
	           privateSessionActive;
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
        // no-op
    }
    
    private void resetPrivateSession() {
        System.out.println("Réinitialisation de la session privée...");
        
        closeFileStream();
        
        activePrivateContext = null;
        privateSessionActive = false;
        privateTargetPeusdo = null;
        hasOpenedPrivateSocket = false;
        hasInitiatedPrivateRequest = false;
        privateToken = -1;
        expectedIncomingToken = -1;
        
        var keys = new ArrayList<>(privateContexts.keySet());
        for (SelectionKey key : keys) {
            silentlyClosePrivate(key);
        }
        
        System.out.println("Session privée réinitialisée.");
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

    private void silentlyClosePrivate(SelectionKey key) {
        try {
            logger.info("Fermeture silencieuse de la connexion privée.");
            PrivateContext context = privateContexts.get(key);
            
            if (context != null && context == activePrivateContext) {
                activePrivateContext = null;
                privateSessionActive = false;
                privateTargetPeusdo = null;
            }
            
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
        InetSocketAddress addr = (InetSocketAddress) ip.getLocalAddress();
        ByteBuffer ipBuffer = ByteBuffer.wrap(addr.getAddress().getAddress());

        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(OPCODE.OK_PRIVATE.getCode());
        bb.putInt(pseudoBuf.remaining());
        bb.put(pseudoBuf);
        bb.putInt(targetBuf.remaining());
        bb.put(targetBuf);
        bb.put((byte) ipBuffer.remaining());
        bb.put(ipBuffer);
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

                ByteBuffer senderBuffer = ByteBuffer.allocate(senderLength);
                for (int i = 0; i < senderLength; i++) {
                    senderBuffer.put(buffer.get());
                }
                senderBuffer.flip();
                String sender = UTF8.decode(senderBuffer).toString();

                int targetLength = buffer.getInt();
                if (buffer.remaining() < targetLength) return;

                ByteBuffer targetBuffer = ByteBuffer.allocate(targetLength);
                for (int i = 0; i < targetLength; i++) {
                    targetBuffer.put(buffer.get());
                }
                targetBuffer.flip();
                String target = UTF8.decode(targetBuffer).toString();

                pendingPrivateRequest = new PrivateRequestSimple(sender, target);
                if (privateTargetPeusdo != null && sender.equals(privateTargetPeusdo)) {
                    System.out.println("Connexion privée déjà en cours avec " + sender + ", pas de redemande nécessaire.");
                    return;
                }
            }
            case OK_PRIVATE -> {
                System.out.println("La connexion privée a été acceptée par le destinataire.");

                int requesterLen = buffer.getInt();
                ByteBuffer requesterBuffer = ByteBuffer.allocate(requesterLen);
                for (int i = 0; i < requesterLen; i++) {
                    requesterBuffer.put(buffer.get());
                }
                requesterBuffer.flip();
                String requester = UTF8.decode(requesterBuffer).toString();

                int targetLen = buffer.getInt();
                ByteBuffer targetBuffer = ByteBuffer.allocate(targetLen);
                for (int i = 0; i < targetLen; i++) {
                    targetBuffer.put(buffer.get());
                }
                targetBuffer.flip();
                String target = UTF8.decode(targetBuffer).toString();

                byte ipType = buffer.get();
                int ipLength = switch (ipType) {
                    case 4 -> 4;
                    case 16 -> 16;
                    default -> throw new IllegalArgumentException("Type IP inconnu: " + ipType);
                };
                ByteBuffer ipBuffer = ByteBuffer.allocate(ipLength);
                for (int i = 0; i < ipLength; i++) {
                    ipBuffer.put(buffer.get());
                }
                ipBuffer.flip();
                byte[] ipBytes = new byte[ipBuffer.remaining()];
                ipBuffer.get(ipBytes);
                InetAddress ipAddress = InetAddress.getByAddress(ipBytes);

                int port = buffer.getInt();
                long token = buffer.getLong();

                if (!hasOpenedPrivateSocket && requester != null && !requester.equals(peusdo) && !isPrivateSessionActive()) {
                    System.out.println("Connexion inversée : ouverture d'une socket vers " + requester);

                    privateSocketAddress = new InetSocketAddress(ipAddress, port);
                    privateToken = token;

                    privateSocket = SocketChannel.open();
                    privateSocket.connect(privateSocketAddress);
                    privateSocket.configureBlocking(false);

                    SelectionKey privateKey = privateSocket.register(privateSelector, SelectionKey.OP_READ);
                    PrivateContext context = new PrivateContext(privateSocket, token, fileDirectory);
                    privateContexts.put(privateKey, context);
                    
                    if (activePrivateContext == null) {
                        activePrivateContext = context;
                    }

                    ByteBuffer openMsg = ByteBuffer.allocate(9);
                    openMsg.put(OPCODE.OPEN.getCode());
                    openMsg.putLong(token);
                    openMsg.flip();
                    privateSocket.write(openMsg);

                    privateSessionActive = true;
                    privateTargetPeusdo = requester;
                    hasOpenedPrivateSocket = true;
                }

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

                ByteBuffer listBuffer = ByteBuffer.allocate(listLength);
                for (int i = 0; i < listLength; i++) {
                    listBuffer.put(buffer.get());
                }
                listBuffer.flip();
                String newUserList = UTF8.decode(listBuffer).toString();
                
                if (newUserList != null && !newUserList.isBlank()) {
                    userList = newUserList.trim();
                    if (shouldDisplayUserList) {
                        System.out.println("Utilisateurs connectés :\n" + userList);
                        shouldDisplayUserList = false;
                    }
                } else {
                    if (shouldDisplayUserList) {
                        System.out.println("Aucun utilisateur connecté actuellement.");
                        shouldDisplayUserList = false;
                    }
                    userList = "";
                }
            }
            case MESSAGE -> {
                if (buffer.remaining() < Integer.BYTES) return;
                int senderLength = buffer.getInt();
                if (buffer.remaining() < senderLength + Integer.BYTES) return;

                ByteBuffer senderBuffer = ByteBuffer.allocate(senderLength);
                for (int i = 0; i < senderLength; i++) {
                    senderBuffer.put(buffer.get());
                }
                senderBuffer.flip();
                String sender = UTF8.decode(senderBuffer).toString();

                int messageLength = buffer.getInt();
                if (buffer.remaining() < messageLength) return;

                ByteBuffer msgBuffer = ByteBuffer.allocate(messageLength);
                for (int i = 0; i < messageLength; i++) {
                    msgBuffer.put(buffer.get());
                }
                msgBuffer.flip();
                String message = UTF8.decode(msgBuffer).toString();

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
        
        Thread.sleep(300);
        
        sendLoginMessage(sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, peusdo);
        System.out.println("Récupération de la liste des utilisateurs connectés...");
        
        Thread.sleep(200);
    }
    
    private boolean isUserConnected(String username) {
        if (userList == null || userList.isBlank()) {
            return false;
        }
        
        String[] users = userList.split("\\s+");
        for (String user : users) {
            if (user.trim().equals(username)) {
                return true;
            }
        }
        return false;
    }
    
    private SocketChannel getPrivateChannel() {
        return isPrivateSessionActive() ? activePrivateContext.sc : null;
    }
    
    private void sendFile(SocketChannel sc, String filename, File file) throws IOException {
        if (sc == null || !sc.isConnected()) {
            System.err.println("Canal de communication non disponible pour l'envoi du fichier.");
            return;
        }

        isSendingFile = true;
        System.out.println("Début d'envoi du fichier : " + filename);

        ByteBuffer fileNameBuf = UTF8.encode(filename);
        ByteBuffer fileNameBuffer = ByteBuffer.allocate(fileNameBuf.remaining());
        fileNameBuffer.put(fileNameBuf);
        fileNameBuffer.flip();
        
        int totalSize = (int) file.length();

        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            ByteBuffer dataBuffer = ByteBuffer.allocate(MAX_BUFFER_SIZE);
            byte[] tempArray = new byte[MAX_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(tempArray)) != -1) {
                dataBuffer.clear();
                dataBuffer.put(tempArray, 0, bytesRead);
                dataBuffer.flip();
                
                ByteBuffer bb = ByteBuffer.allocate(1 + 1 + fileNameBuffer.remaining() + 4 + 4 + bytesRead);

                bb.put(OPCODE.FILE.getCode());
                bb.put((byte) fileNameBuffer.remaining());
                fileNameBuffer.rewind();
                bb.put(fileNameBuffer);
                bb.putInt(totalSize);
                bb.putInt(bytesRead);
                bb.put(dataBuffer);

                bb.flip();
                while (bb.hasRemaining()) {
                    sc.write(bb);
                }
            }
            System.out.println("Fichier envoyé avec succès : " + filename);
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
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 8);
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
    
    private void sendUserMessage(String line) throws IOException {
        SocketChannel targetChannel;
        if (privateSessionActive && getPrivateChannel() != null) {
            targetChannel = getPrivateChannel();
        } else {
            targetChannel = MainSc;
        }
        sendTextMessage(targetChannel, peusdo, line);
    }
    
    private void handleUserCommand(String line) throws IOException {
        if (line == null || line.isBlank()) return;

        if (pendingPrivateRequest != null && !waitingForPrivateReply) {
            pendingPrompt = pendingPrivateRequest;
            pendingPrivateRequest = null;
            waitingForPrivateReply = true;
            System.out.println("Demande de connexion privée reçue de : " + pendingPrompt.peusdoRequester() + ". Accepter ? (o/n)");
            return;
        }

        if (waitingForPrivateReply) {
            String reply = line.trim().toLowerCase();
            if (pendingPrompt != null) {
                if (reply.equals("o") || reply.equals("oui")) {
                    privateToken = System.currentTimeMillis();
                    expectedIncomingToken = privateToken;
                    sendOKPrivateMessage(MainSc, 4, privateToken, peusdo, pendingPrompt.peusdoRequester(), MainSc, privatePort);
                    System.out.println("Connexion privée acceptée avec " + pendingPrompt.peusdoRequester());
                    privateTargetPeusdo = pendingPrompt.peusdoRequester();
                } else if (reply.equals("n") || reply.equals("non")) {
                    sendKOPrivateMessage(MainSc, peusdo, pendingPrompt.peusdoRequester());
                    System.out.println("Connexion privée refusée.");
                } else {
                    System.out.println("Réponse invalide. Tapez 'o' pour oui ou 'n' pour non.");
                    return;
                }
            }
            pendingPrompt = null;
            waitingForPrivateReply = false;
            return;
        }

        if (line.equalsIgnoreCase("/getusers")) {
            shouldDisplayUserList = true;
            sendLoginMessage(MainSc, OPCODE.GET_CONNECTED_USERS.getCode(), System.currentTimeMillis(), peusdo);
            return;
        }
        
        if (line.equalsIgnoreCase("/users") || line.equalsIgnoreCase("/list")) {
            if (userList == null || userList.isBlank()) {
                System.out.println("Aucune liste d'utilisateurs en cache. Tapez /getusers pour la récupérer.");
            } else {
                System.out.println("Utilisateurs connectés (cache) :\n" + userList);
            }
            return;
        }

        if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/disconnect")) {
            if (isPrivateSessionActive()) {
                System.out.println("Fermeture de la session privée avec " + privateTargetPeusdo);
                resetPrivateSession();
            } else {
                System.out.println("Aucune session privée active.");
            }
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
            
            if (isPrivateSessionActive()) {
                System.out.println("Vous avez déjà une session privée active avec " + privateTargetPeusdo + ". Tapez /quit pour la fermer.");
                return;
            }
            
            if (userList == null || userList.isBlank()) {
                System.out.println("Actualisation de la liste des utilisateurs...");
                sendLoginMessage(MainSc, OPCODE.GET_CONNECTED_USERS.getCode(), System.currentTimeMillis(), peusdo);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (userList != null && !userList.isBlank() && !isUserConnected(login)) {
                System.out.println("Attention : " + login + " ne semble pas être dans la liste des utilisateurs connectés.");
                System.out.println("Tentative d'envoi de la demande quand même...");
            }
            
            sendRequestPrivateMessage(MainSc, peusdo, login);
            hasInitiatedPrivateRequest = true;
            System.out.println("Demande de connexion privée envoyée à " + login + "...");
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

            if (!isPrivateSessionActive()) {
                System.out.println("Session privée non active.");
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

        sendUserMessage(line);
    }



    public static void main(String[] args) {
        if (args.length < 3) {
            logger.log(Level.SEVERE, "Usage: java ClientTest <pseudonyme> <mot_de_passe> <dossier_fichier>");
            return;
        }
        
        ClientTest client = new ClientTest();
        client.mainLoop(args[0], args[1], args[2]);
    }

    private void mainLoop(String username, String password, String fileDirectory) {
        this.fileDirectory = fileDirectory;

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

                        if (key.isValid() && key.isReadable() && key.channel() == MainSc) {
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