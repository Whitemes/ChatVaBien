package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents a request indicating acceptance of a private connection between two users.
 *
 * @param peusdoRequester the pseudonym of the user who initiated the private request
 * @param peusdoTarget    the pseudonym of the user who was the target of the private request
 * @param token           a unique token associated with the private connection
 */
public record OKPrivateRequest(String peusdoRequester, String peusdoTarget, Ip sc, long token) implements Request {
	
	private static final Charset UTF8 = StandardCharsets.UTF_8;

	/**
	 * Handles the acceptance of a private request using the provided server context.
	 *
	 * @param context the server context used to process the acceptance
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleOKPrivateRequest();
	}

	@Override
	public ByteBuffer toByteBuffer() {
		var by = UTF8.encode(peusdoTarget);
		var ip = UTF8.encode(sc.address().toString());
		return ByteBuffer.allocate(Integer.BYTES + by.remaining() + Byte.BYTES + ip.remaining() + Integer.BYTES + Long.BYTES).putInt(by.remaining()).put(by).put(sc.version()).put(ip).putInt(sc.port()).putLong(token).flip();
	}
}
