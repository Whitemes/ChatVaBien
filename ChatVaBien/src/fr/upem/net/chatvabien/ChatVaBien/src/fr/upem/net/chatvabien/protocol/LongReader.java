package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

public class LongReader implements Reader<Long> {
    private enum State {DONE, WAITING, ERROR}
    private State state = State.WAITING;
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(Long.BYTES);
    private long value;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        //System.out.println("WHECKPOINT");
        int remaining = bb.remaining();
        int toRead = Math.min(remaining, internalBuffer.remaining());
        for (int i = 0; i < toRead; i++) {
            internalBuffer.put(bb.get());
        }

        if (internalBuffer.hasRemaining()) {
            return ProcessStatus.REFILL;
        }

        internalBuffer.flip();
        value = internalBuffer.getLong();
        state = State.DONE;
        return ProcessStatus.DONE;
    }

    @Override
    public Long get() {
        if (state != State.DONE) throw new IllegalStateException();
        return value;
    }

    @Override
    public void reset() {
        state = State.WAITING;
        internalBuffer.clear();
    }
}