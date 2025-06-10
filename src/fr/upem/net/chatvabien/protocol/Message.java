package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * Messages scellés remplaçant l'interface Request
 * Utilise le polymorphisme au lieu de instanceof
 */
public sealed interface Message
        permits LoginMessage, PublicMessage, PrivateRequestMessage,
        OKPrivateMessage, KOPrivateMessage, GetUsersMessage {

    /**
     * Sérialise le message dans un ByteBuffer
     */
    ByteBuffer serialize();

    /**
     * Traite le message (remplace handle())
     */
    void process(ServerMessageProcessor processor);
}