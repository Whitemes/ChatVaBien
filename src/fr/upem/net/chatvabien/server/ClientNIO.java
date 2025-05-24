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

    public static void main(String[] args) throws IOException {
        try(Selector selector = Selector.open();
            SocketChannel sc = SocketChannel.open()) {
            sc.configureBlocking(false);
            sc.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));

            SelectionKey key = sc.register(selector, SelectionKey.OP_CONNECT);
            key.attach(new Context(key));

            // Thread pour lire la console
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
        }
    }

    private static void silentlyClose(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
    }

    // Classe interne pour gérer les buffers
    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);

        Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
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
            while (bufferIn.remaining() >= Integer.BYTES) {
                bufferIn.mark();
                int len = bufferIn.getInt();
                if (bufferIn.remaining() < len) {
                    bufferIn.reset();
                    break;
                }
                byte[] msgBytes = new byte[len];
                bufferIn.get(msgBytes);
                String msg = new String(msgBytes, StandardCharsets.UTF_8);
                System.out.println("📩 Message: " + msg);
            }
            bufferIn.compact();
        }

        void doWrite() throws IOException {
            bufferOut.flip();
            sc.write(bufferOut);
            bufferOut.compact();
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
