package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents a request to broadcast a message from a specific user.
 *
 * @param peusdo  the pseudonym of the user sending the message
 * @param message the message content to be broadcast
 */
public record MessageRequest(String peusdo, String message) implements Request {
	
	private static final Charset UTF8 = StandardCharsets.UTF_8;


    /**
     * Handles the message broadcasting request using the provided server context.
     *
     * @param context the server context used to process and broadcast the message
     */
    @Override
    public void handle(ServerContext context) {
        context.broadcastMessage(peusdo, message);
    }

    public ByteBuffer toByteBuffer() {
        ByteBuffer textEncoded = UTF8.encode(message);
        
        int totalSize = Integer.BYTES + textEncoded.remaining();
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.clear();
        buffer.order(ByteOrder.BIG_ENDIAN);
                
	    buffer.putInt(textEncoded.remaining());
	    buffer.put(textEncoded);
        
        buffer.flip();
        
        return buffer;
    }
}
