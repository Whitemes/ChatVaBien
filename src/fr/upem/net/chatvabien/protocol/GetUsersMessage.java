package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

public record GetUsersMessage() implements Message {
    @Override
    public ByteBuffer serialize() {
        return ByteBuffer.allocate(0);
    }

    @Override
    public void process(ServerMessageProcessor processor) {
        processor.processGetUsers();
    }
}