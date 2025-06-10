package fr.upem.net.chatvabien.protocol;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record OKPrivateMessage(String targetPseudo, InetSocketAddress address, long token) implements Message {
    @Override
    public ByteBuffer serialize() {
        var targetBytes = StandardCharsets.UTF_8.encode(targetPseudo);
        var addressBytes = address.getAddress().getAddress();

        return ByteBuffer.allocate(
                        Integer.BYTES + targetBytes.remaining() +  // target
                                Byte.BYTES + addressBytes.length +        // IP
                                Integer.BYTES +                           // port
                                Long.BYTES                                // token
                )
                .putInt(targetBytes.remaining())
                .put(targetBytes)
                .put((byte) (addressBytes.length == 4 ? 0x04 : 0x06))
                .put(addressBytes)
                .putInt(address.getPort())
                .putLong(token)
                .flip();
    }

    @Override
    public void process(ServerMessageProcessor processor) {
        processor.processOKPrivate(targetPseudo, address, token);
    }
}
