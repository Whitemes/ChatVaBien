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

    private static void sendMessage(int version, SocketChannel sc, byte opcode, long id, String message) throws IOException {
        var ip = sc.socket().getLocalAddress().getAddress();
        ByteBuffer encodedMessage = null;
        int size;

        if (!message.isEmpty()) {
            encodedMessage = StandardCharsets.UTF_8.encode(message);
            size = 1 + Long.BYTES + 1 + ip.length + Integer.BYTES + encodedMessage.remaining();
        } else {
            size = 1 + Long.BYTES + 1 + ip.length;
        }

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES + size);
        bb.putInt(size);
        bb.put(opcode);
        bb.putLong(id);
        bb.put((byte) version);
        bb.put(ip);
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

        byte rawOpcode = buffer.get();
        OPCODE op = OPCODE.fromCode(rawOpcode);
        if (op == null) {
            System.out.printf("Opcode inconnu : %02X\n", rawOpcode);
            return;
        }

        switch (op) {
            case LOGIN_ACCEPTED -> System.out.println("✔ Connexion acceptée");
            case LOGIN_REFUSED -> System.out.println("✖ Connexion refusée");
            case LOGINAUTH -> System.out.println("✔ Authentification réussie");
            case MESSAGE -> {
                if (buffer.remaining() < Long.BYTES + Integer.BYTES) {
                    System.out.println("MESSAGE trop court.");
                    return;
                }
                long senderId = buffer.getLong();
                int len = buffer.getInt();
                if (buffer.remaining() < len) {
                    System.out.println("MESSAGE incomplet.");
                    return;
                }
                byte[] msgBytes = new byte[len];
                buffer.get(msgBytes);
                String msg = new String(msgBytes, StandardCharsets.UTF_8);
                System.out.println("📨 Message de " + senderId + " : " + msg);
            }
            default -> System.out.println("Réponse : " + op);
        }
    }

    public static void main(String[] args) {
        try (SocketChannel sc = SocketChannel.open()) {
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
            System.out.println("✅ Connexion établie avec le serveur.");

            Scanner scanner = new Scanner(System.in);
            startReceiver(sc);

            long id = System.currentTimeMillis(); // identifiant unique
            sendMessage(4, sc, OPCODE.LOGIN.getCode(), id, "");

            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("@")) {
                    // Message privé : @login message
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("⚠ Syntaxe invalide. Utilisez : @login message");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String msg = line.substring(spaceIndex + 1);
                    // Simule une demande de connexion privée + envoi message
                    System.out.println("🔒 Demande de connexion privée avec " + login + "...");
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login);
                    Thread.sleep(200); // petit délai (idéalement, attendre une réponse)
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, "[Privé à " + login + "] " + msg);
                } else if (line.startsWith("/")) {
                    // Envoi de fichier : /login fichier
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex == -1 || spaceIndex == 1) {
                        System.out.println("⚠ Syntaxe invalide. Utilisez : /login fichier.txt");
                        continue;
                    }
                    String login = line.substring(1, spaceIndex);
                    String filename = line.substring(spaceIndex + 1);

                    File file = new File(filename);
                    if (!file.exists()) {
                        System.out.println("⚠ Fichier introuvable : " + filename);
                        continue;
                    }

                    System.out.println("📁 Envoi du fichier à " + login + "...");
                    sendMessage(4, sc, OPCODE.REQUEST_PRIVATE.getCode(), id, login);
                    Thread.sleep(200); // attendre OK_PRIVATE

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] content = fis.readAllBytes();
                        String base64 = java.util.Base64.getEncoder().encodeToString(content);
                        sendMessage(4, sc, OPCODE.FILE.getCode(), id, "[Fichier: " + file.getName() + "] " + base64);
                    } catch (IOException e) {
                        System.out.println("❌ Erreur lecture fichier : " + e.getMessage());
                    }

                } else {
                    // Message public
                    sendMessage(4, sc, OPCODE.MESSAGE.getCode(), id, line);
                }
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("❌ Erreur client : " + e.getMessage());
        }
    }
}
