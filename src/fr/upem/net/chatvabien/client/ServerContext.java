package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.logging.Logger;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Contexte de communication avec le serveur principal
 * VERSION PROPRE - Debug retiré
 */
public class ServerContext implements ChannelHandler {
    private static final Logger logger = Logger.getLogger(ServerContext.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final SelectionKey key;
    private final SocketChannel sc;
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final Queue<ByteBuffer> outQueue = new ArrayDeque<>();

    private final TrameReader trameReader = new TrameReader();
    private final String login;
    private final ServerMessageHandler messageHandler;

    private boolean loginSent = false;
    private boolean connected = false;

    public ServerContext(SelectionKey key, String login, ServerMessageHandler messageHandler) {
        this.key = key;
        this.sc = (SocketChannel) key.channel();
        this.login = login;
        this.messageHandler = messageHandler;
    }

    @Override
    public void handleConnect() throws IOException {
        if (sc.finishConnect()) {
            connected = true;
            updateInterestOps();
            logger.info("Connecté au serveur");
        }
    }

    @Override
    public void handleRead() throws IOException {
        var read = sc.read(bufferIn);
        if (read == -1) {
            logger.info("Serveur fermé");
            return;
        }

        if (read > 0) {
            processIn();
        }
    }

    @Override
    public void handleWrite() throws IOException {
        // Envoyer login dès que connecté
        if (!loginSent && connected) {
            sendLogin();
        }

        processOut();

        // Gestion correcte du buffer
        if (bufferOut.position() > 0) {
            bufferOut.flip();
            var written = sc.write(bufferOut);

            if (bufferOut.hasRemaining()) {
                bufferOut.compact();
            } else {
                bufferOut.clear();
            }
        }

        updateInterestOps();
    }

    private void processIn() {
        bufferIn.flip();

        while (true) {
            var status = trameReader.process(bufferIn);
            if (status == Reader.ProcessStatus.DONE) {
                var trame = trameReader.get();
                messageHandler.handleServerMessage(trame);
                trameReader.reset();
            } else if (status == Reader.ProcessStatus.REFILL) {
                break;
            } else {
                logger.severe("Erreur lecture serveur");
                break;
            }
        }

        bufferIn.compact();
    }

    private void sendLogin() {
        if (loginSent) return;

        var loginTrame = Trame.clientMessage(OPCODE.LOGIN, login, new LoginMessage());
        var buffer = loginTrame.toByteBuffer();

        // ✅ SOLUTION ROBUSTE: Tenter d'écrire immédiatement
        try {
            var written = sc.write(buffer);
            if (buffer.hasRemaining()) {
                // Si pas tout écrit, mettre le reste en queue
                outQueue.offer(buffer);
            }
        } catch (IOException e) {
            // En cas d'erreur, utiliser la queue normale
            outQueue.offer(buffer);
        }

        loginSent = true;
        updateInterestOps();
    }

    private void processOut() {
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

    public void queueMessage(Message message) {
        var trame = Trame.clientMessage(OPCODE.MESSAGE, login, message);
        var buffer = trame.toByteBuffer();
        outQueue.offer(buffer);
        updateInterestOps();
    }

    public void queuePrivateRequest(String target) {
        var request = new PrivateRequestMessage(target);
        var trame = Trame.clientMessage(OPCODE.REQUEST_PRIVATE, login, request);
        var buffer = trame.toByteBuffer();
        outQueue.offer(buffer);
        updateInterestOps();
    }

    public void queueOKPrivate(String target, InetSocketAddress address, long token) {
        var response = new OKPrivateMessage(target, address, token);
        var trame = Trame.clientMessage(OPCODE.OK_PRIVATE, login, response);
        var buffer = trame.toByteBuffer();
        outQueue.offer(buffer);
        updateInterestOps();
    }

    public void queueKOPrivate(String target) {
        var response = new KOPrivateMessage(target);
        var trame = Trame.clientMessage(OPCODE.KO_PRIVATE, login, response);
        var buffer = trame.toByteBuffer();
        outQueue.offer(buffer);
        updateInterestOps();
    }

    public void requestUserList() {
        var request = new GetUsersMessage();
        var trame = Trame.clientMessage(OPCODE.GET_CONNECTED_USERS, login, request);
        var buffer = trame.toByteBuffer();
        outQueue.offer(buffer);
        updateInterestOps();
    }

    private void updateInterestOps() {
        var ops = SelectionKey.OP_READ;
        // ✅ CORRIGÉ: Garder la logique originale qui fonctionne
        if ((!loginSent && connected) || !outQueue.isEmpty() || bufferOut.position() > 0) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }
}