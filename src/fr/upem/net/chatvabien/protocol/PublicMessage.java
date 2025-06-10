package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record PublicMessage(String text) implements Message {
    @Override
    public ByteBuffer serialize() {
        var textBytes = StandardCharsets.UTF_8.encode(text);
        return ByteBuffer.allocate(Integer.BYTES + textBytes.remaining())
                .putInt(textBytes.remaining())
                .put(textBytes)
                .flip();
    }

    @Override
    public void process(ServerMessageProcessor processor) {
        processor.processPublicMessage(text);
    }
}
