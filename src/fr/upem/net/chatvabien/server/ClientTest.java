package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.OPCODE;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClientTest {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static String peusdo;

    private static void sendMessage(int version, SocketChannel sc, byte opcode, long id, String message, String peusdo) throws IOException {
        var ip = sc.socket().getLocalAddress().getAddress();
        ByteBuffer encodedMessage = null;
        int size;
        var encodedPeusdo = StandardCharsets.UTF_8.encode(peusdo);

        if (!message.isEmpty()) {
            encodedMessage = StandardCharsets.UTF_8.encode(message);
            size = 1 + Long.BYTES + 1 + ip.length + Integer.BYTES + encodedPeusdo.remaining() + Integer.BYTES + encodedMessage.remaining();
        } else {
            size = 1 + Long.BYTES + 1 + ip.length + Integer.BYTES + encodedPeusdo.remaining();
        }
        
        

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + size);
        bb.putInt(size);
        bb.put(opcode);
        bb.putLong(id);
        bb.put((byte) version);
        bb.put(ip);
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
            ByteBuffer buffer = ByteBuffer.allocate(1024);
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

    private static void decodeMessage(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            System.out.println("Message vide.");
            return;
        }

        byte rawOpcode = buffer.get();  // Lire l'opcode
        OPCODE op = OPCODE.fromCode(rawOpcode);
        if (op == null) {
            System.out.printf("Opcode inconnu : %02X\n", rawOpcode);
            return;
        }

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
                
            case CONNECTED_USERS_LIST:
            	System.out.println("Voici les utilisateurs connectés en ce moment : ");
            	break;

            case MESSAGE:
                if (buffer.remaining() < Long.BYTES + Integer.BYTES) {
                    System.out.println("Erreur : message trop court.");
                    return;
                }

                // Lire l'ID de l'expéditeur
                long senderId = buffer.getLong();
                System.out.println("ID de l'expéditeur : " + senderId);

                // Lire la longueur du message
                int msgLength = buffer.getInt();
                System.out.println("Longueur du message : " + msgLength);

                if (buffer.remaining() < msgLength) {
                    System.out.println("Erreur : le message est incomplet.");
                    return;
                }

                // Lire le message
                byte[] msgBytes = new byte[msgLength];
                buffer.get(msgBytes);  // Extraire les données du buffer
                String message = new String(msgBytes, StandardCharsets.UTF_8);
                System.out.println("Message reçu de " + senderId + " : " + message);
                break;

            default:
                System.out.println("Réponse : " + op);
                break;
        }
    }
    
    private static void login(SocketChannel sc, long id) throws IOException, InterruptedException {
        Scanner scanner = new Scanner(System.in);

        // Demande du pseudonyme
        System.out.print("Entrez votre pseudonyme: ");
        String username = scanner.nextLine().trim();

        // Demande du mot de passe
        System.out.print("Entrez votre mot de passe (laisser vide pour un login simple) : ");
        String password = scanner.nextLine().trim();

        // Vérifier si le mot de passe est vide
        String credentials = username; // Le login consiste uniquement du pseudonyme dans tous les cas.
        peusdo = username;
        byte opcodeToSend;
        if (!password.isEmpty()) {
            // Si le mot de passe n'est pas vide, on envoie LOGINAUTH
            credentials += ":" + password;
            opcodeToSend = OPCODE.LOGINAUTH.getCode();
        } else {
            // Si le mot de passe est vide, on envoie LOGIN
            opcodeToSend = OPCODE.LOGIN.getCode();
        }

        // Envoi de la demande de login au serveur
        sendMessage(4, sc, opcodeToSend, id, "", peusdo);

        // Attendre une réponse du serveur pour l'authentification
        System.out.println("Tentative de connexion...");
        Thread.sleep(200); // Attendre la réponse

        // Une fois connecté, envoyer une requête pour obtenir les pseudos des utilisateurs connectés
        sendMessage(4, sc, OPCODE.GET_CONNECTED_USERS.getCode(), id, "", peusdo);
    }

    public static void main(String[] args) {
        try (SocketChannel sc = SocketChannel.open()) {
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
            System.out.println("Connexion établie avec le serveur.");

            Scanner scanner = new Scanner(System.in);
            startReceiver(sc);

            long id = System.currentTimeMillis(); // identifiant unique

            login(sc, id); // Appel à la méthode de login

            // Une fois connecté, continuer le programme
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("@")) {
                    // Message privé : @login message
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : @login message");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String msg = line.substring(spaceIndex + 1);
                    // Simule une demande de connexion privée + envoi message
                    System.out.println("Demande de connexion privée avec " + login + "...");
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login, peusdo);
                    Thread.sleep(200); // petit délai (idéalement, attendre une réponse)
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, "[Privé à " + login + "] " + msg, peusdo);
                } else if (line.startsWith("/")) {
                    // Envoi de fichier : /login fichier
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("Syntaxe invalide. Utilisez : /login fichier.txt");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String filename = line.substring(spaceIndex + 1);

                    File file = new File(filename);
                    if (!file.exists()) {
                        System.out.println("Fichier introuvable : " + filename);
                        continue;
                    }

                    System.out.println("Envoi du fichier à " + login + "...");
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login, peusdo);
                    Thread.sleep(200); // attendre OK_PRIVATE

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] content = fis.readAllBytes();
                        String base64 = java.util.Base64.getEncoder().encodeToString(content);
                        sendMessage(4, sc, OPCODE.FILE.getCode(), id, "[Fichier: " + file.getName() + "] " + base64, peusdo);
                    } catch (IOException e) {
                        System.out.println("Erreur lecture fichier : " + e.getMessage());
                    }

                } else {
                    // Message public
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, line, peusdo);
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Erreur client : " + e.getMessage());
        }
    }
}
