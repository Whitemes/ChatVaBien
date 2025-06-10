package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * A request to retrieve the list of currently connected users.
 */
public record GetUsersRequest() implements Request {

	/**
	 * Handles the user retrieval request using the provided server context.
	 *
	 * @param context the server context used to process the request
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleGetUsers();
	}

	@Override
	public ByteBuffer toByteBuffer() {
		// TODO Auto-generated method stub
		return null;
	}
}
