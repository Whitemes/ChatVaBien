package fr.upem.net.chatvabien.protocol;

import java.net.InetSocketAddress;

/**
 * Interface pour traiter les messages côté serveur
 * Remplace ServerContext avec une approche plus fonctionnelle
 */
public interface ServerMessageProcessor {
    void processLogin();
    void processPublicMessage(String text);
    void processPrivateRequest(String targetPseudo);
    void processOKPrivate(String targetPseudo, InetSocketAddress address, long token);
    void processKOPrivate(String targetPseudo);
    void processGetUsers();
}