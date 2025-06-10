package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.OPCODE;

/**
 * Contexte pour une connexion privée P2P
 */
public class PrivateContext implements ChannelHandler {
    private static final Logger logger = Logger.getLogger(PrivateContext.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final SelectionKey key;
    private final SocketChannel sc;
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final Queue<ByteBuffer> outQueue = new ArrayDeque<>();

    private final String login;
    private String remotePseudo;
    private long expectedToken = -1;
    private long outgoingToken = -1;
    private boolean opened = false;

    public PrivateContext(SelectionKey key, String login, String remotePseudo) {
        this.key = key;
        this.sc = (SocketChannel) key.channel();
        this.login = login;
        this.remotePseudo = remotePseudo;
    }

    // ========== GETTERS/SETTERS ==========

    public void setRemotePseudo(String remotePseudo) {
        this.remotePseudo = remotePseudo;
    }

    public void setOutgoingToken(long token) {
        this.outgoingToken = token;
    }

    public void setExpectedToken(long token) {
        this.expectedToken = token;
    }

    public boolean isOpened() {
        return opened;
    }

    public String getRemotePseudo() {
        return remotePseudo;
    }

    // ========== CHANNEL HANDLER ==========

    @Override
    public void handleConnect() throws IOException {
        if (sc.finishConnect()) {
            sendOpen();
            updateInterestOps();
            logger.info("Connexion privée établie vers " + remotePseudo);
        }
    }

    @Override
    public void handleRead() throws IOException {
        var read = sc.read(bufferIn);
        if (read == -1) {
            System.out.println("💔 Connexion privée fermée avec " + remotePseudo);
            return;
        }

        if (read > 0) {
            processPrivateIn();
        }
    }

    @Override
    public void handleWrite() throws IOException {
        processPrivateOut();

        if (bufferOut.position() > 0) {
            bufferOut.flip();
            sc.write(bufferOut);
            if (bufferOut.hasRemaining()) {
                bufferOut.compact();
            } else {
                bufferOut.clear();
            }
        }

        updateInterestOps();
    }

    // ========== LOGIQUE PRIVÉE ==========

    private void sendOpen() {
        if (outgoingToken != -1) {
            var openBuffer = ByteBuffer.allocate(9);
            openBuffer.put(OPCODE.OPEN.getCode());
            openBuffer.putLong(outgoingToken);
            openBuffer.flip();
            outQueue.offer(openBuffer);
            updateInterestOps();
        }
    }

    private void processPrivateIn() {
        bufferIn.flip();

        while (bufferIn.hasRemaining()) {
            if (!opened) {
                if (bufferIn.remaining() < 9) break;

                var opcode = bufferIn.get();
                if (opcode == OPCODE.OPEN.getCode()) {
                    var token = bufferIn.getLong();
                    if (expectedToken == -1 || token == expectedToken) {
                        opened = true;
                        System.out.println("🔗 Connexion privée établie avec " + remotePseudo);
                    } else {
                        logger.warning("Token invalide reçu: " + token + ", attendu: " + expectedToken);
                        return;
                    }
                }
            } else {
                // TODO: Traiter messages privés (MESSAGE ou FILE)
                // Pour l'instant, on break pour éviter une boucle infinie
                break;
            }
        }

        bufferIn.compact();
    }

    private void processPrivateOut() {
        while (bufferOut.hasRemaining() && !outQueue.isEmpty()) {
            var buffer = outQueue.peek();

            if (bufferOut.remaining() >= buffer.remaining()) {
                bufferOut.put(buffer);
                outQueue.poll();
            } else {
                break;
            }
        }
    }

    // ========== API PUBLIQUE ==========

    public void sendPrivateMessage(String message) {
        if (!opened) {
            System.out.println("❌ Connexion privée pas encore établie avec " + remotePseudo);
            return;
        }

        var msgBytes = message.getBytes();
        var loginBytes = login.getBytes();
        var buffer = ByteBuffer.allocate(1 + 4 + loginBytes.length + 4 + msgBytes.length);

        buffer.put(OPCODE.MESSAGE.getCode());
        buffer.putInt(loginBytes.length);
        buffer.put(loginBytes);
        buffer.putInt(msgBytes.length);
        buffer.put(msgBytes);
        buffer.flip();

        outQueue.offer(buffer);
        updateInterestOps();
    }

    private void updateInterestOps() {
        var ops = SelectionKey.OP_READ;
        if (!outQueue.isEmpty() || bufferOut.position() > 0) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }
}