package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * A {@code Reader} implementation that reads a 8-byte {@code long} value from a {@link ByteBuffer}.
 * <p>
 * This reader accumulates bytes until a {@code long} can be fully read.
 */
public class LongReader implements Reader<Long> {

    /**
     * Represents the state of the {@code LongReader}.
     */
    private enum State {DONE, WAITING, ERROR}

    private State state = State.WAITING;
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(Long.BYTES);
    private long value;

    /**
     * Attempts to read a long value from the given {@link ByteBuffer}.
     * Bytes are accumulated internally until enough data is available.
     *
     * @param bb the {@link ByteBuffer} to read from
     * @return {@link ProcessStatus#DONE} if a long value was successfully read,
     *         {@link ProcessStatus#REFILL} if more data is needed,
     *         or throws an exception if in an invalid state
     * @throws IllegalStateException if called when the reader is already done or in error state
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
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

    /**
     * Returns the long value read by this reader.
     *
     * @return the long value
     * @throws IllegalStateException if the value is not yet available
     */
    @Override
    public Long get() {
        if (state != State.DONE) throw new IllegalStateException();
        return value;
    }

    /**
     * Resets the reader, clearing any accumulated bytes and making it ready to read another long value.
     */
    @Override
    public void reset() {
        state = State.WAITING;
        internalBuffer.clear();
    }
}
