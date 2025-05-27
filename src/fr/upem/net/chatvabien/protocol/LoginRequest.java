package fr.upem.net.chatvabien.protocol;

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
}
