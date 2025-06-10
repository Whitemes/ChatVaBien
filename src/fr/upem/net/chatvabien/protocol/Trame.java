package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record Trame(OPCODE opcode, String sender, Message message) {

    /**
     * Sérialise la trame complète : OPCODE + sender + message
     */
    public ByteBuffer toByteBuffer() {
        var senderBytes = StandardCharsets.UTF_8.encode(sender);
        var messageBuffer = message.serialize();

        var totalSize = Byte.BYTES +                           // opcode
                Integer.BYTES + senderBytes.remaining() + // sender
                messageBuffer.remaining();             // message

        return ByteBuffer.allocate(totalSize)
                .put(opcode.getCode())
                .putInt(senderBytes.remaining())
                .put(senderBytes)
                .put(messageBuffer)
                .flip();
    }

    /**
     * Factory pour créer des trames de réponse du serveur
     */
    public static Trame serverResponse(OPCODE opcode, Message message) {
        return new Trame(opcode, "Server", message);
    }

    /**
     * Factory pour créer des trames client
     */
    public static Trame clientMessage(OPCODE opcode, String sender, Message message) {
        return new Trame(opcode, sender, message);
    }
}