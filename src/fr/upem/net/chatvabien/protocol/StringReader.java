package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A {@code Reader} implementation that reads a UTF-8 encoded string from a {@link ByteBuffer}.
 * <p>
 * The string is expected to be serialized as:
 * <ul>
 *     <li>First, a 4-byte integer indicating the number of bytes in the string (size),</li>
 *     <li>Then, {@code size} bytes containing the UTF-8 encoded string.</li>
 * </ul>
 */
public class StringReader implements Reader<String> {

    /**
     * The internal states for reading a string.
     */
    private enum State {WAITING_SIZE, WAITING_STRING, DONE, ERROR}

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private final IntReader intReader = new IntReader();
    private State state = State.WAITING_SIZE;
    private int size;
    private ByteBuffer internalBuffer;
    private String value;

    /**
     * Processes bytes from the provided {@link ByteBuffer} to read a UTF-8 string.
     * First reads the length (as an integer), then reads the string bytes,
     * and finally decodes them using UTF-8.
     *
     * @param bb the {@link ByteBuffer} containing data to be read
     * @return {@link ProcessStatus#DONE} if the string has been fully read,
     *         {@link ProcessStatus#REFILL} if more data is needed,
     *         or {@link ProcessStatus#ERROR} if an error occurred (e.g., invalid size)
     * @throws IllegalStateException if called when the reader is already in the DONE or ERROR state
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }

        if (state == State.WAITING_SIZE) {
            var status = intReader.process(bb);
            if (status != ProcessStatus.DONE) {
                return status;
            }

            size = intReader.get();
            intReader.reset();

            if (size < 0 || size > 1024) {
                state = State.ERROR;
                return ProcessStatus.ERROR;
            }

            internalBuffer = ByteBuffer.allocate(size);
            state = State.WAITING_STRING;
        }

        if (state == State.WAITING_STRING) {
            int toRead = Math.min(bb.remaining(), internalBuffer.remaining());

            int limit = bb.limit();
            bb.limit(bb.position() + toRead);
            internalBuffer.put(bb);
            bb.limit(limit);

            if (internalBuffer.hasRemaining()) {
                return ProcessStatus.REFILL;
            }

            internalBuffer.flip();
            value = UTF8.decode(internalBuffer).toString();
            state = State.DONE;
            return ProcessStatus.DONE;
        }

        return ProcessStatus.ERROR;
    }

    /**
     * Returns the string value read by this reader.
     *
     * @return the decoded UTF-8 string
     * @throws IllegalStateException if the value is not yet available or if an error occurred
     */
    @Override
    public String get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    /**
     * Resets the reader, clearing any accumulated data and making it ready to read another string.
     */
    @Override
    public void reset() {
        state = State.WAITING_SIZE;
        intReader.reset();
        internalBuffer = null;
        value = null;
    }
}
