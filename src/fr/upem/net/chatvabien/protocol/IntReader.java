package fr.upem.net.chatvabien.protocol;


import java.nio.ByteBuffer;

public class IntReader implements Reader<Integer> {

    private enum State {
        DONE, WAITING, ERROR
    };

    private State state = State.WAITING;
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(Integer.BYTES); // write-mode
    private int value;

    @Override
    public ProcessStatus process(ByteBuffer buffer) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        while (buffer.hasRemaining() && internalBuffer.hasRemaining()) {
            internalBuffer.put(buffer.get());
        }
        if (internalBuffer.hasRemaining()) {
            return ProcessStatus.REFILL;
        }
        internalBuffer.flip();
        value = internalBuffer.getInt();
        state = State.DONE;
        return ProcessStatus.DONE;
    }

    @Override
    public Integer get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    @Override
    public void reset() {
        state = State.WAITING;
        internalBuffer.clear();
    }
}