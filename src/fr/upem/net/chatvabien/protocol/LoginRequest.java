package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * Represents a request to initiate a login operation.
 */
public record LoginRequest() implements Request {

	/**
	 * Handles the login request using the provided server context.
	 *
	 * @param context the server context used to process the login operation
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleLogin();
	}

	@Override
	public ByteBuffer toByteBuffer() {
		// TODO Auto-generated method stub
		return ByteBuffer.allocate(0);
	}

}
