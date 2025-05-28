package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * A {@code Reader} implementation that reads a 4-byte {@code int} value from a {@link ByteBuffer}.
 * <p>
 * This reader accumulates bytes until an {@code int} can be fully read.
 */
public class IntReader implements Reader<Integer> {

    /**
     * Represents the state of the {@code IntReader}.
     */
    private enum State {
        DONE, WAITING, ERROR
    };

    private State state = State.WAITING;
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(Integer.BYTES); // write-mode
    private int value;

    /**
     * Attempts to read an integer from the given {@link ByteBuffer}.
     * Bytes are accumulated internally until enough data is available.
     *
     * @param buffer the {@link ByteBuffer} to read from
     * @return {@link ProcessStatus#DONE} if an integer was successfully read,
     *         {@link ProcessStatus#REFILL} if more data is needed,
     *         or throws an exception if in an invalid state
     * @throws IllegalStateException if called when the reader is already done or in error state
     */
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

    /**
     * Returns the integer value read by this reader.
     *
     * @return the integer value
     * @throws IllegalStateException if the value is not yet available
     */
    @Override
    public Integer get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    /**
     * Resets the reader, clearing any accumulated bytes and making it ready to read another integer.
     */
    @Override
    public void reset() {
        state = State.WAITING;
        internalBuffer.clear();
    }
}
