package fr.upem.net.chatvabien.protocol;

/**
 * Defines the contract for handling server-side actions in response to protocol requests.
 * <p>
 * Implementations of this interface are responsible for processing
 * the various types of requests received by the server.
 */
public interface ServerContext {
    /**
     * Handles a login request from a client.
     */
    void handleLogin();

    /**
     * Handles a request to retrieve the list of currently connected users.
     */
    void handleGetUsers();

    /**
     * Handles a request to initiate a private connection between two users.
     */
    void handlePrivateRequest();

    /**
     * Handles the acceptance of a private connection request.
     */
    void handleOKPrivateRequest();

    /**
     * Handles the refusal or failure of a private connection request.
     */
    void handleKOPrivateRequest();

    /**
     * Broadcasts a message from the specified sender to all connected users.
     *
     * @param message the message content to broadcast
     * @param sender  the pseudonym of the user sending the message
     */
    void broadcastMessage(String sender, String message);
}
