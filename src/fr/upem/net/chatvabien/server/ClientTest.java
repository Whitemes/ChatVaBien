package fr.upem.net.chatvabien.server;

import fr.upem.net.chatvabien.protocol.OPCODE;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClientTest {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static String peusdo;
    private static String fileDirectory;

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
        //bb.putLong(id);
        //bb.put((byte) version);
        //bb.put(ip);
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

    private static void decodeMessage(ByteBuffer buffer) {
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
                String userList = new String(listBytes, StandardCharsets.UTF_8);

                System.out.println("Voici les utilisateurs connectés en ce moment :\n" + userList);
                break;

            case MESSAGE:
                if (buffer.remaining() < Long.BYTES + Integer.BYTES) {
                    System.out.println("Erreur : message trop court.");
                    return;
                }

                int senderId = buffer.getInt();
                int msgLength = buffer.getInt();

                if (buffer.remaining() < msgLength) {
                    System.out.println("Erreur : le message est incomplet.");
                    return;
                }

                byte[] msgBytes = new byte[msgLength];
                buffer.get(msgBytes);
                String message = new String(msgBytes, StandardCharsets.UTF_8);
                System.out.println("Message reçu de " + senderId + " : " + message);

                // Traitement de fichiers reçus
                if (message.startsWith("[Fichier:")) {
                    int endName = message.indexOf("]");
                    if (endName != -1) {
                        String header = message.substring(0, endName + 1);
                        String base64 = message.substring(endName + 2);
                        String filename = header.substring(10, header.length() - 1).trim();

                        byte[] data = java.util.Base64.getDecoder().decode(base64);
                        File receivedFile = new File(fileDirectory, "reçu_" + filename);

                        try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                            fos.write(data);
                            System.out.println("Fichier reçu et sauvegardé : " + receivedFile.getAbsolutePath());
                        } catch (IOException e) {
                            System.err.println("Erreur lors de la sauvegarde du fichier : " + e.getMessage());
                        }
                    }
                }
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
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, "[Privé à " + login + "] " + msg, peusdo);
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