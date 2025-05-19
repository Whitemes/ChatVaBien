package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class IpReader implements Reader<InetAddress> {

    private enum State { WAITING_TYPE, WAITING_ADDRESS, DONE, ERROR }

    public State state = State.WAITING_TYPE;
    private byte ipType;
    private ByteBuffer ipBuffer;
    private InetAddress ip;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }

        switch (state) {
            case WAITING_TYPE -> {
                if (bb.remaining() < 1) {
                    return ProcessStatus.REFILL;
                }
                ipType = bb.get();
                if (ipType == 0x04) {
                    ipBuffer = ByteBuffer.allocate(4);
                } else if (ipType == 0x06) {
                    ipBuffer = ByteBuffer.allocate(16);
                } else {
                    state = State.ERROR;
                    return ProcessStatus.ERROR;
                }
                
                state = State.WAITING_ADDRESS;
                System.out.println("State: " + state + ", bb.remaining: " + bb.remaining());
                return ProcessStatus.REFILL;
            }

            case WAITING_ADDRESS -> {
                int toRead = Math.min(bb.remaining(), ipBuffer.remaining());
                for (int i = 0; i < toRead; i++) {
                    ipBuffer.put(bb.get());
                }
                if (ipBuffer.hasRemaining()) {
                    return ProcessStatus.REFILL;
                }
                try {
                    ip = InetAddress.getByAddress(ipBuffer.array());
                } catch (UnknownHostException e) {
                    state = State.ERROR;
                    return ProcessStatus.ERROR;
                }
                state = State.DONE;
                return ProcessStatus.DONE;
            }

            default -> throw new IllegalStateException();
        }
    }

    @Override
    public InetAddress get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return ip;
    }

    @Override
    public void reset() {
        state = State.WAITING_TYPE;
        ipBuffer = null;
        ip = null;
    }
}