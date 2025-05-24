package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class IpReader implements Reader<Ip> {

    private enum State {WAITING_TYPE, WAITING_ADDRESS, WAITING_PORT, DONE, ERROR}

    private final ByteReader byteReader = new ByteReader();
    private final IntReader portReader = new IntReader();

    private State state = State.WAITING_TYPE;
    private byte ipType;
    private ByteBuffer ipBuffer;
    private InetAddress ip;
    private int port;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }

        switch (state) {
            case WAITING_TYPE -> {
                var status = byteReader.process(bb);
                if (status != ProcessStatus.DONE) {
                    return status;
                }
                ipType = byteReader.get();
                if (ipType == 0x04) {
                    ipBuffer = ByteBuffer.allocate(4);
                } else if (ipType == 0x06) {
                    ipBuffer = ByteBuffer.allocate(16);
                } else {
                    state = State.ERROR;
                    return ProcessStatus.ERROR;
                }
                state = State.WAITING_ADDRESS;
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
                state = State.WAITING_PORT;
                return ProcessStatus.REFILL;
            }

            case WAITING_PORT -> {
                var status = portReader.process(bb);
                if (status != ProcessStatus.DONE) {
                    return status;
                }
                port = portReader.get();
                state = State.DONE;
                return ProcessStatus.DONE;
            }

            default -> throw new IllegalStateException();
        }
    }

    @Override
    public Ip get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return new Ip(ipType, ip, port);
    }

    @Override
    public void reset() {
        state = State.WAITING_TYPE;
        byteReader.reset();
        portReader.reset();
        ipBuffer = null;
        ip = null;
    }
}