package fr.upem.net.chatvabien.client;

import fr.upem.net.chatvabien.protocol.*;

/**
 * Interface pour gérer les messages reçus du serveur
 */
@FunctionalInterface
public interface ServerMessageHandler {
    void handleServerMessage(Trame trame);
}