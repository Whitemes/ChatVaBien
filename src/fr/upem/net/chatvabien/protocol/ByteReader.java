package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * A {@code Reader} implementation that reads a single {@code byte} value from a {@link ByteBuffer}.
 */
public class ByteReader implements Reader<Byte> {
    /**
     * The possible states of the {@code ByteReader}.
     */
    private enum State {DONE, WAITING, ERROR}
    private State state = State.WAITING;
    private byte value;

    /**
     * Attempts to read a byte from the provided {@link ByteBuffer}.
     *
     * @param bb the {@link ByteBuffer} to read from
     * @return {@link ProcessStatus#DONE} if a byte was successfully read,
     *         {@link ProcessStatus#REFILL} if more data is needed,
     *         or throws an exception if in an invalid state
     * @throws IllegalStateException if the reader is already done or in error state
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        if (bb.remaining() < Byte.BYTES) {
            return ProcessStatus.REFILL;
        }
        value = bb.get();
        state = State.DONE;
        return ProcessStatus.DONE;
    }

    /**
     * Retrieves the byte value read by this reader.
     *
     * @return the byte value
     * @throws IllegalStateException if the value is not ready to be retrieved
     */
    @Override
    public Byte get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    /**
     * Resets the reader to the initial waiting state, allowing it to read another value.
     */
    @Override
    public void reset() {
        state = State.WAITING;
    }
}
