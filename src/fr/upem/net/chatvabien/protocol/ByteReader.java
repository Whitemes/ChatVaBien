package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;


public class ByteReader implements Reader<Byte> {
    private enum State {DONE, WAITING, ERROR}
    private State state = State.WAITING;
    private byte value;

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

    @Override
    public Byte get() {
        if (state != State.DONE) {
        	throw new IllegalStateException();
        }
        return value;
    }

    @Override
    public void reset() {
        state = State.WAITING;
    }
}