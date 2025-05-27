package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

public record User(long id, String pseudo, SocketChannel sc, boolean isAuth) {

    public static class ProtocolEncoder {
        private static final Charset UTF8 = Charset.forName("UTF8");

        /**
         * Encode un message de broadcast/public (ex: message du serveur à tous).
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
         * Encode une requête de connexion privée (REQUEST_PRIVATE ou KO_PRIVATE).
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
         * Encode une réponse OK à une demande de connexion privée (OK_PRIVATE).
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
         * Encode une réponse KO à une demande de connexion privée (KO_PRIVATE).
         */
        public static ByteBuffer encodeKOPrivateRequest(String from, String to, byte opcode) {
            return encodePrivateRequest(from, to, opcode);
        }

        /**
         * Encode la liste des utilisateurs connectés (GET_CONNECTED_USERS -> CONNECTED_USERS_LIST).
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
         * Encode le statut de login (LOGIN_ACCEPTED ou LOGIN_REFUSED).
         */
        public static ByteBuffer encodeLoginStatus(boolean accepted, byte opcodeAccepted, byte opcodeRefused) {
            ByteBuffer bb = ByteBuffer.allocate(1);
            bb.put(accepted ? opcodeAccepted : opcodeRefused);
            bb.flip();
            return bb;
        }

        // Ajoute ici d'autres méthodes spécifiques au besoin du protocole...
        // Par exemple, pour des futurs OPCODEs, il suffit de dupliquer le pattern.

        // Utility class: ne pas instancier
        private ProtocolEncoder() {}
    }
}
