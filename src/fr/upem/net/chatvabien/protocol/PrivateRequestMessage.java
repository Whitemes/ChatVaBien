package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record PrivateRequestMessage(String targetPseudo) implements Message {
    @Override
    public ByteBuffer serialize() {
        var targetBytes = StandardCharsets.UTF_8.encode(targetPseudo);
        return ByteBuffer.allocate(Integer.BYTES + targetBytes.remaining())
                .putInt(targetBytes.remaining())
                .put(targetBytes)
                .flip();
    }

    @Override
    public void process(ServerMessageProcessor processor) {
        processor.processPrivateRequest(targetPseudo);
    }
}