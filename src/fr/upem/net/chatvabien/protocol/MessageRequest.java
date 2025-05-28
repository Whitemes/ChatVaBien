package fr.upem.net.chatvabien.protocol;

/**
 * Represents a request to broadcast a message from a specific user.
 *
 * @param peusdo  the pseudonym of the user sending the message
 * @param message the message content to be broadcast
 */
public record MessageRequest(String peusdo, String message) implements Request {

    /**
     * Handles the message broadcasting request using the provided server context.
     *
     * @param context the server context used to process and broadcast the message
     */
    @Override
    public void handle(ServerContext context) {
        context.broadcastMessage(peusdo, message);
    }
}
