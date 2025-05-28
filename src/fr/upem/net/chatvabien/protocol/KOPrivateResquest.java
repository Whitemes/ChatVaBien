package fr.upem.net.chatvabien.protocol;

/**
 * Represents a request indicating the refusal or failure of a private connection attempt between two users.
 *
 * @param peusdoRequester the pseudonym of the user who initiated the private request
 * @param peusdoTarget    the pseudonym of the user who was the target of the private request
 */
public record KOPrivateResquest(String peusdoRequester, String peusdoTarget) implements Request {

	/**
	 * Handles the refusal of a private request using the provided server context.
	 *
	 * @param context the server context used to process the refusal
	 */
	@Override
	public void handle(ServerContext context) {
		context.handleKOPrivateRequest();
	}
}
