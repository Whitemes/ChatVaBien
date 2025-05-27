package fr.upem.net.chatvabien.protocol;

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
}
