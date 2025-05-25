package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClientNIO {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 7777;
    private static final int BUFFER_SIZE = 1024;
    private static String USERNAME;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java ClientNIO <login>");
            return;
        }

        USERNAME = args[0];

        try (Selector selector = Selector.open();
             SocketChannel sc = SocketChannel.open()) {

            sc.configureBlocking(false);
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
            SelectionKey key = sc.register(selector, SelectionKey.OP_CONNECT);
            key.attach(new Context(key));

            new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    System.out.print("> ");
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    Context ctx = (Context) key.attachment();
                    ctx.sendLine(line);
                }
            }).start();

            while (!Thread.interrupted()) {
                selector.select(ClientNIO::treatKey);
            }
        }
    }

    private static void treatKey(SelectionKey key) {
        var ctx = (Context) key.attachment();
        try {
            if (key.isValid() && key.isConnectable()) {
                doConnect(key);
            }
            if (key.isValid() && key.isReadable()) {
                ctx.doRead();
            }
            if (key.isValid() && key.isWritable()) {
                ctx.doWrite();
            }
        } catch (IOException e) {
            silentlyClose(key);
        }
    }

    private static void doConnect(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        if (sc.finishConnect()) {
            System.out.println("✅ Connecté au serveur.");
            key.interestOps(SelectionKey.OP_READ);
            Context ctx = (Context) key.attachment();
            ctx.login(USERNAME); // Envoi du LOGIN (-1)
        }
    }

    private static void silentlyClose(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);

        Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        void login(String login) {
            var encodedLogin = StandardCharsets.UTF_8.encode(login);
            ByteBuffer bb = ByteBuffer.allocate(1 + Integer.BYTES + encodedLogin.remaining());
            bb.put((byte) -1); // OPCODE LOGIN (-1)
            bb.putInt(encodedLogin.remaining());
            bb.put(encodedLogin);
            bb.flip();
            queueMessage(bb);
        }

        void sendLine(String line) {
            var encoded = StandardCharsets.UTF_8.encode(line);
            bufferOut.putInt(encoded.remaining());
            bufferOut.put(encoded);
            updateInterestOps();
        }

        void doRead() throws IOException {
            if (sc.read(bufferIn) == -1) {
                sc.close();
                return;
            }
            bufferIn.flip();

            while (bufferIn.remaining() >= 1) {
                byte opcode = bufferIn.get();

                switch (opcode) {
                    case 1 -> System.out.println("✅ LOGIN_ACCEPTED");
                    case 2 -> {
                        System.out.println("❌ LOGIN_REFUSED");
                        sc.close();
                        return;
                    }
                    default -> System.out.println("📥 Opcode inconnu reçu: " + opcode);
                }
            }

            bufferIn.compact();
        }

        void doWrite() throws IOException {
            bufferOut.flip();
            sc.write(bufferOut);
            bufferOut.compact();
            updateInterestOps();
        }

        void queueMessage(ByteBuffer bb) {
            bufferOut.put(bb);
            updateInterestOps();
        }

        void updateInterestOps() {
            int ops = SelectionKey.OP_READ;
            if (bufferOut.position() > 0) {
                ops |= SelectionKey.OP_WRITE;
            }
            key.interestOps(ops);
        }
    }
}
