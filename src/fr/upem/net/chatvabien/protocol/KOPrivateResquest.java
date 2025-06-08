package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents a request indicating the refusal or failure of a private connection attempt between two users.
 *
 * @param peusdoRequester the pseudonym of the user who initiated the private request
 * @param peusdoTarget    the pseudonym of the user who was the target of the private request
 */
public record KOPrivateResquest(String peusdoRequester, String peusdoTarget) implements Request {

	private static final Charset UTF8 = StandardCharsets.UTF_8;
	
	/**
	 * Handles the refusal of a private request using the provided server context.
	 *
	 * @param context the server context used to process the refusal
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleKOPrivateRequest();
	}

	@Override
	public ByteBuffer toByteBuffer() {
		var by = UTF8.encode(peusdoTarget);
		return ByteBuffer.allocate(Integer.BYTES + by.remaining()).putInt(by.remaining()).put(by).flip();
	}
}
