package fr.upem.net.chatvabien.protocol;

/**
 * Represents a sealed protocol request in the ChatVaBien system.
 * <p>
 * Each implementation corresponds to a specific type of request that can be processed by the server.
 * </p>
 *
 * <p>
 * The permitted implementations are:
 * <ul>
 *     <li>{@link LoginRequest}</li>
 *     <li>{@link MessageRequest}</li>
 *     <li>{@link GetUsersRequest}</li>
 *     <li>{@link PrivateRequest}</li>
 *     <li>{@link OKPrivateRequest}</li>
 *     <li>{@link KOPrivateResquest}</li>
 * </ul>
 * </p>
 */
public sealed interface Request
        permits LoginRequest, MessageRequest, GetUsersRequest, PrivateRequest, OKPrivateRequest, KOPrivateResquest {

    /**
     * Handles the request using the provided server context.
     *
     * @param context the server context used to process the request
     */
    void handle(ServerContext context);
}
