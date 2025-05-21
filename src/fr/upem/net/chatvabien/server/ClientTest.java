package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.OPCODE;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

    private static void sendMessage(int version, SocketChannel sc, byte opcode, long id, String message, String peusdo) throws IOException {
        var ip = sc.socket().getLocalAddress().getAddress();
        ByteBuffer encodedMessage = null;
        int size;
        var encodedPeusdo = StandardCharsets.UTF_8.encode(peusdo);

        if (!message.isEmpty()) {
            encodedMessage = StandardCharsets.UTF_8.encode(message);
            //size = 1 + Long.BYTES + 1 + ip.length + Integer.BYTES + encodedPeusdo.remaining() + Integer.BYTES + encodedMessage.remaining();
        } else {
            //size = 1 + Long.BYTES + 1 + ip.length + Integer.BYTES + encodedPeusdo.remaining();
        }

        //ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + size);
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put(opcode);
        
        if(opcode == OPCODE.OK_PRIVATE.getCode()) {
        	bb.put((byte) version);
        	bb.put(ip);
        	bb.putLong(id);
        }
        
        bb.putInt(encodedPeusdo.remaining());
        bb.put(encodedPeusdo);
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
            case LOGIN_ACCEPTED:
                System.out.println("Connexion acceptée.");
                break;

            case LOGIN_REFUSED:
                System.out.println("Connexion refusée. Vérifiez votre pseudonyme ou mot de passe.");
                break;

            case LOGINAUTH:
                System.out.println("Authentification réussie.");
                break;
            
            case REQUEST_PRIVATE:
            	int senderPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer senderPrivateBytes = ByteBuffer.allocate(senderPrivateLength);
                while(i < senderPrivateLength) {
                	senderPrivateBytes.put(buffer.get());
                	i++;
                }
                senderPrivateBytes.flip();
                String senderPrivate = UTF8.decode(senderPrivateBytes).toString();
                int targetPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer targetrBytes = ByteBuffer.allocate(targetPrivateLength);
                while(i < targetPrivateLength) {
                	targetrBytes.put(buffer.get());
                	i++;
                }
                targetrBytes.flip();
                String targetPrivate = UTF8.decode(targetrBytes).toString();
                
                System.out.println("Demande de connexion privée reçue de : " + senderPrivate);
                System.out.print("Accepter ? (o/n) > ");
                
                synchronized (System.in) {
                    Scanner scan = new Scanner(System.in);
                    String reply = scan.nextLine().trim().toLowerCase();
                    isPrivateResquested = true;

                    if (reply.equals("o") || reply.equals("oui")) {
                        sendMessage(4, MainSc, OPCODE.OK_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo);
                        System.out.println("Connexion privée acceptée avec " + senderPrivate);
                    } else {
                    	sendMessage(4, MainSc, OPCODE.KO_PRIVATE.getCode(), System.currentTimeMillis(), "", peusdo);
                        System.out.println("Connexion privée refusée.");
                    }
                }
                
                isPrivateResquested = false;

                
            	break;
            	
            case OK_PRIVATE:
            	int senderOKPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer senderOKPrivateBytes = ByteBuffer.allocate(senderOKPrivateLength);
                while(i < senderOKPrivateLength) {
                	senderOKPrivateBytes.put(buffer.get());
                	i++;
                }
                senderOKPrivateBytes.flip();
                String senderOKPrivate = UTF8.decode(senderOKPrivateBytes).toString();
                int targetOKPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer targetOKBytes = ByteBuffer.allocate(targetOKPrivateLength);
                while(i < targetOKPrivateLength) {
                	targetOKBytes.put(buffer.get());
                	i++;
                }
                targetOKBytes.flip();
                String targetOKPrivate = UTF8.decode(targetOKBytes).toString();
            	break;
            	
            case KO_PRIVATE:
            	int senderKOPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer senderKOPrivateBytes = ByteBuffer.allocate(senderKOPrivateLength);
                while(i < senderKOPrivateLength) {
                	senderKOPrivateBytes.put(buffer.get());
                	i++;
                }
                senderKOPrivateBytes.flip();
                String senderKOPrivate = UTF8.decode(senderKOPrivateBytes).toString();
                int targetKOPrivateLength = buffer.getInt();
            	i = 0;
                ByteBuffer targetKOBytes = ByteBuffer.allocate(targetKOPrivateLength);
                while(i < targetKOPrivateLength) {
                	targetKOBytes.put(buffer.get());
                	i++;
                }
                targetKOBytes.flip();
                String targetKOPrivate = UTF8.decode(targetKOBytes).toString();
            	break;

            case CONNECTED_USERS_LIST:
                if (buffer.remaining() < Integer.BYTES) {
                    System.out.println("Erreur : données insuffisantes pour la taille de la liste.");
                    return;
                }

                int listLength = buffer.getInt();
                if (buffer.remaining() < listLength) {
                    System.out.println("Erreur : données incomplètes pour la liste des utilisateurs.");
                    return;
                }

                ByteBuffer listBytes = ByteBuffer.allocate(listLength);
                i = 0;
                while(i < listLength) {
                	listBytes.put(buffer.get());
                	i++;
                }
                listBytes.flip();
                String userList = UTF8.decode(listBytes).toString();

                System.out.println("Utilisateurs connectés :\n" + userList);
                break;

            case MESSAGE:
                int senderLength = buffer.getInt();
                i = 0;
                ByteBuffer senderBytes = ByteBuffer.allocate(senderLength);
                while(i < senderLength) {
                	senderBytes.put(buffer.get());
                	i++;
                }
                senderBytes.flip();
                String sender = UTF8.decode(senderBytes).toString();
                int messageLength = buffer.getInt();
                i = 0;
                ByteBuffer msgBytes = ByteBuffer.allocate(messageLength);
                while(i < messageLength) {
                	msgBytes.put(buffer.get());
                	i++;
                }
                msgBytes.flip();
                String message = UTF8.decode(msgBytes).toString();
                System.out.println(sender + " : " + message);
                break;

            default:
                System.out.println("Réponse : " + op);
                break;
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

        sendMessage(4, sc, opcodeToSend, id, "", peusdo);
        System.out.println("Tentative de connexion...");
        Thread.sleep(200);

        sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo);
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
                if (line.isEmpty()) continue;
                
                if (line.equalsIgnoreCase("/getusers")) {
                    sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo);
                    continue;
                }


                if (line.startsWith("@")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : @login message");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String msg = line.substring(spaceIndex + 1);
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login, peusdo);
                    Thread.sleep(200);
                    //sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, "[Privé à " + login + "] " + msg, peusdo);
                } else if (line.startsWith("/")) {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : /login fichier.txt");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String filename = line.substring(spaceIndex + 1);

                    File file = new File(fileDirectory, filename);
                    if (!file.exists()) {
                        System.out.println("Fichier introuvable : " + filename);
                        continue;
                    }

                    System.out.println("Envoi du fichier à " + login + "...");
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login, peusdo);
                    Thread.sleep(200);

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] content = fis.readAllBytes();
                        String base64 = java.util.Base64.getEncoder().encodeToString(content);
                        sendMessage(4, sc, OPCODE.FILE.getCode(), id, "[Fichier: " + file.getName() + "] " + base64, peusdo);
                    } catch (IOException e) {
                        System.out.println("Erreur lecture fichier : " + e.getMessage());
                    }

                } else {
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, line, peusdo);
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Erreur client : " + e.getMessage());
        }
    }
}