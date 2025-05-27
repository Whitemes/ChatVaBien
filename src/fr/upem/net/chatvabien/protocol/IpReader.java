package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * A {@code Reader} implementation for reading an {@link Ip} object from a {@link ByteBuffer}.
 * <p>
 * This reader expects the following format in the buffer:
 * <ul>
 *     <li>1 byte: IP version (0x04 for IPv4, 0x06 for IPv6)</li>
 *     <li>4 or 16 bytes: IP address (depending on the version)</li>
 *     <li>4 bytes: port (integer)</li>
 * </ul>
 */
public class IpReader implements Reader<Ip> {

    /**
     * The internal states for reading an {@link Ip} object.
     */
    private enum State {WAITING_TYPE, WAITING_ADDRESS, WAITING_PORT, DONE, ERROR}

    private final ByteReader byteReader = new ByteReader();
    private final IntReader portReader = new IntReader();

    private State state = State.WAITING_TYPE;
    private byte ipType;
    private ByteBuffer ipBuffer;
    private InetAddress ip;
    private int port;

    /**
     * Reads data from the provided {@link ByteBuffer} to construct an {@link Ip} object.
     * This method processes the buffer in multiple steps: first reading the IP type,
     * then the address, and finally the port number.
     *
     * @param bb the {@link ByteBuffer} containing the serialized IP information
     * @return {@link ProcessStatus#DONE} if the IP has been fully read,
     *         {@link ProcessStatus#REFILL} if more data is needed,
     *         or {@link ProcessStatus#ERROR} if an error occurs
     * @throws IllegalStateException if called when the reader is already done or in error state
     */
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

    /**
     * Returns the {@link Ip} object that was read from the buffer.
     *
     * @return the fully constructed {@link Ip} object
     * @throws IllegalStateException if the reading process is not complete
     */
    @Override
    public Ip get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return new Ip(ipType, ip, port);
    }

    /**
     * Resets the reader, clearing its internal state and making it ready to read another {@link Ip} object.
     */
    @Override
    public void reset() {
        state = State.WAITING_TYPE;
        byteReader.reset();
        portReader.reset();
        ipBuffer = null;
        ip = null;
    }
}
