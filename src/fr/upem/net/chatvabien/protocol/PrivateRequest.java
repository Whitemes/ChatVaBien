package fr.upem.net.chatvabien.protocol;

/**
 * Represents a request to initiate a private connection between two users.
 *
 * @param peusdoRequester the pseudonym of the user initiating the private request
 * @param peusdoTarget    the pseudonym of the user who is the target of the private request
 */
public record PrivateRequest(String peusdoRequester, String peusdoTarget) implements Request {

	/**
	 * Handles the private connection request using the provided server context.
	 *
	 * @param context the server context used to process the private request
	 */
	@Override
	public void handle(ServerContext context) {
		context.handlePrivateRequest();
	}
}
