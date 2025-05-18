package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class StringReader implements Reader<String> {

    private enum State {WAITING_SIZE, WAITING_STRING, DONE, ERROR}

    private final static Charset UTF8 = StandardCharsets.UTF_8;
    private final IntReader intReader = new IntReader();
    private State state = State.WAITING_SIZE;
    private int size;
    private ByteBuffer internalBuffer;
    private String value;

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

    @Override
    public String get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    @Override
    public void reset() {
        state = State.WAITING_SIZE;
        intReader.reset();
        internalBuffer = null;
        value = null;
    }
}