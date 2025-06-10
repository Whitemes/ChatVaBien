package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

public record LoginMessage() implements Message {
    @Override
    public ByteBuffer serialize() {
        return ByteBuffer.allocate(0); // Pas de payload
    }

    @Override
    public void process(ServerMessageProcessor processor) {
        processor.processLogin();
    }
}