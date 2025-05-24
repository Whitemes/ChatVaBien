package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.OPCODE;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

public class ClientTest {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static final Charset UTF8 = Charset.forName("UTF8");
    private static final int MAX_BUFFER_SIZE = 1024;
    private static String peusdo;
    private static String fileDirectory;
    private static SocketChannel MainSc;
    private static boolean isPrivateResquested = false;

    private static void sendMessage(int version, SocketChannel sc, byte opcode, long id, String message, String peusdo, String target_peusdo) throws IOException {
        ByteBuffer encodedMessage = null;
        var encodedPeusdo = StandardCharsets.UTF_8.encode(peusdo);
        

        InetAddress inetAddress = sc.socket().getLocalAddress();
        byte[] ipBytes = inetAddress.getAddress(); // 4 ou 16 octets
        int port = sc.socket().getLocalPort();     // PORT à envoyer

        if (!message.isEmpty()) {
            encodedMessage = StandardCharsets.UTF_8.encode(message);
        }

        // Taille large pour simplifier
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(opcode);
        
        bb.putInt(encodedPeusdo.remaining());
        bb.put(encodedPeusdo);
        
        if(opcode == OPCODE.REQUEST_PRIVATE.getCode() || opcode == OPCODE.OK_PRIVATE.getCode() || opcode == OPCODE.KO_PRIVATE.getCode()) {
        	var encodedTargetPeusdo = StandardCharsets.UTF_8.encode(target_peusdo);
        	bb.putInt(encodedTargetPeusdo.remaining());
        	bb.put(encodedTargetPeusdo);
        }

        if (opcode == OPCODE.OK_PRIVATE.getCode()) {
            bb.put((byte) version);       // 1 byte IP version
            bb.put(ipBytes);              // 4/16 bytes IP address
            bb.putInt(port);              // 4 bytes port
            bb.putLong(id);              // 8 bytes token
            System.out.println("Envoi OK_PRIVATE:");
            System.out.println("- IP: " + Arrays.toString(ipBytes));
            System.out.println("- Port: " + port);
            System.out.println("- Pseudo: " + peusdo);
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
        
        int i = 0;

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
	
	            System.out.println("Demande de connexion privée reçue de : " + sender);
	            System.out.print("Accepter ? (o/n) > ");
	
	            isPrivateResquested = true;
	
	            try (Scanner scan = new Scanner(System.in)) {
	                String reply = scan.nextLine().trim().toLowerCase();
	                if (reply.equals("o") || reply.equals("oui")) {
	                    sendMessage(4, MainSc, OPCODE.OK_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo, target);
	                    System.out.println("Connexion privée acceptée avec " + sender);
	                } else {
	                    sendMessage(4, MainSc, OPCODE.KO_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo, null);
	                    System.out.println("Connexion privée refusée.");
	                }
	            }
	
	            isPrivateResquested = false;
	        }
	
	        case OK_PRIVATE -> {
	            System.out.println("La connexion privée a été acceptée par le destinataire.");
	        }
	
	        case KO_PRIVATE -> {
	            System.out.println("La connexion privée a été refusée par le destinataire.");
	        }
	
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
        byte opcodeToSend;

        if (!password.isEmpty()) {
            credentials += ":" + password;
            opcodeToSend = OPCODE.LOGINAUTH.getCode();
        } else {
            opcodeToSend = OPCODE.LOGIN.getCode();
        }

        sendMessage(4, sc, opcodeToSend, id, "", peusdo, null);
        System.out.println("Tentative de connexion...");
        Thread.sleep(200);

        sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo, null);
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

            Scanner scanner = new Scanner(System.in);
            startReceiver(sc);

            long id = System.currentTimeMillis();

            login(sc, id, username, password);

            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                	continue;
                }
                
                if (line.equalsIgnoreCase("/getusers")) {
                    sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo, null);
                    continue;
                }


                if (line.startsWith("@")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : @login");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    if(login.equals(peusdo)) {
                    	System.out.println("Impossible de d'avoir une connexion privée avec nous même");
                    	continue;
                    }
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login, peusdo, login);
                } else if (line.startsWith("/")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : /login fichier.txt");
                        continue;
                    }

                } else {
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, line, peusdo, null);
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Erreur client : " + e.getMessage());
        }
    }
}