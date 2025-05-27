package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

/**
 * Represents a user in the ChatVaBien protocol.
 *
 * @param id     the unique identifier of the user
 * @param pseudo the pseudonym of the user
 * @param sc     the socket channel associated with the user
 * @param isAuth whether the user is authenticated
 */
public record User(long id, String pseudo, SocketChannel sc, boolean isAuth) {

    /**
     * Provides static utility methods to encode protocol messages for transmission.
     * <p>
     * This class should not be instantiated.
     */
    public static class ProtocolEncoder {
        private static final Charset UTF8 = Charset.forName("UTF8");

        /**
         * Encodes a broadcast or public message (e.g., a message sent from the server to all clients).
         *
         * @param sender  the pseudonym of the sender
         * @param message the message content
         * @param opcode  the operation code for the message
         * @return a {@link ByteBuffer} containing the encoded message
         */
        public static ByteBuffer encodeBroadcastMessage(String sender, String message, byte opcode) {
            var encodedSender = UTF8.encode(sender);
            var encodedMessage = UTF8.encode(message);
            ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + encodedSender.remaining() + Integer.BYTES + encodedMessage.remaining());
            bb.put(opcode);
            bb.putInt(encodedSender.remaining());
            bb.put(encodedSender);
            bb.putInt(encodedMessage.remaining());
            bb.put(encodedMessage);
            bb.flip();
            return bb;
        }

        /**
         * Encodes a private connection request message (REQUEST_PRIVATE or KO_PRIVATE).
         *
         * @param from   the pseudonym of the requester
         * @param to     the pseudonym of the target user
         * @param opcode the operation code for the request
         * @return a {@link ByteBuffer} containing the encoded request
         */
        public static ByteBuffer encodePrivateRequest(String from, String to, byte opcode) {
            var fromBytes = UTF8.encode(from);
            var toBytes = UTF8.encode(to);
            ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + fromBytes.remaining() + Integer.BYTES + toBytes.remaining());
            bb.put(opcode);
            bb.putInt(fromBytes.remaining());
            bb.put(fromBytes);
            bb.putInt(toBytes.remaining());
            bb.put(toBytes);
            bb.flip();
            return bb;
        }

        /**
         * Encodes an OK response to a private connection request (OK_PRIVATE).
         *
         * @param from      the pseudonym of the requester
         * @param to        the pseudonym of the target user
         * @param clientIp  the {@link Ip} object representing the client's address and port
         * @param token     the unique token associated with the private connection
         * @param opcode    the operation code for the OK response
         * @return a {@link ByteBuffer} containing the encoded OK response
         */
        public static ByteBuffer encodeOKPrivateRequest(String from, String to, Ip clientIp, long token, byte opcode) {
            var fromBytes = UTF8.encode(from);
            var toBytes = UTF8.encode(to);

            byte[] rawIp = clientIp.address().getAddress();
            byte ipType = clientIp.version();
            int port = clientIp.port();

            ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES +
                    Integer.BYTES + fromBytes.remaining() +
                    Integer.BYTES + toBytes.remaining() +
                    Byte.BYTES + rawIp.length +
                    Integer.BYTES +
                    Long.BYTES);

            bb.put(opcode);
            bb.putInt(fromBytes.remaining());
            bb.put(fromBytes);
            bb.putInt(toBytes.remaining());
            bb.put(toBytes);
            bb.put(ipType);
            bb.put(rawIp);
            bb.putInt(port);
            bb.putLong(token);
            bb.flip();
            return bb;
        }

        /**
         * Encodes a KO (refusal) response to a private connection request (KO_PRIVATE).
         *
         * @param from   the pseudonym of the requester
         * @param to     the pseudonym of the target user
         * @param opcode the operation code for the KO response
         * @return a {@link ByteBuffer} containing the encoded KO response
         */
        public static ByteBuffer encodeKOPrivateRequest(String from, String to, byte opcode) {
            return encodePrivateRequest(from, to, opcode);
        }

        /**
         * Encodes a response containing the list of connected users
         * (typically in response to a GET_CONNECTED_USERS request).
         *
         * @param userList a comma-separated list of connected user pseudonyms
         * @param opcode   the operation code for the user list response
         * @return a {@link ByteBuffer} containing the encoded user list
         */
        public static ByteBuffer encodeUserList(String userList, byte opcode) {
            var encodedUserList = UTF8.encode(userList);
            ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + encodedUserList.remaining());
            bb.put(opcode);
            bb.putInt(encodedUserList.remaining());
            bb.put(encodedUserList);
            bb.flip();
            return bb;
        }

        /**
         * Encodes a login status response (LOGIN_ACCEPTED or LOGIN_REFUSED).
         *
         * @param accepted      {@code true} if the login is accepted; {@code false} otherwise
         * @param opcodeAccepted the operation code for an accepted login
         * @param opcodeRefused  the operation code for a refused login
         * @return a {@link ByteBuffer} containing the encoded login status
         */
        public static ByteBuffer encodeLoginStatus(boolean accepted, byte opcodeAccepted, byte opcodeRefused) {
            ByteBuffer bb = ByteBuffer.allocate(1);
            bb.put(accepted ? opcodeAccepted : opcodeRefused);
            bb.flip();
            return bb;
        }

        // Utility class: prevent instantiation.
        private ProtocolEncoder() {}
    }
}
