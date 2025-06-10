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

        var buffer = ByteBuffer.allocate(totalSize);

        var result = buffer
                .put(opcode.getCode())
                .putInt(senderBytes.remaining())
                .put(senderBytes)
                .put(messageBuffer)
                .flip();

        // ✅ DEBUG: Dump du buffer final
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.remaining(); i++) {
            byte b = result.get(result.position() + i);
            sb.append(String.format("%02X ", b));
        }
        System.out.println("Buffer final: " + sb.toString());
        System.out.println("===============================");

        return result;
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