package fr.upem.net.chatvabien.protocol;

/**
 * Represents a request indicating acceptance of a private connection between two users.
 *
 * @param peusdoRequester the pseudonym of the user who initiated the private request
 * @param peusdoTarget    the pseudonym of the user who was the target of the private request
 * @param token           a unique token associated with the private connection
 */
public record OKPrivateRequest(String peusdoRequester, String peusdoTarget, long token) implements Request {

	/**
	 * Handles the acceptance of a private request using the provided server context.
	 *
	 * @param context the server context used to process the acceptance
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleOKPrivateRequest();
	}
}
