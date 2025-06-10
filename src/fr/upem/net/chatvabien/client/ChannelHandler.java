package fr.upem.net.chatvabien.client;

import java.io.IOException;

/**
 * Interface commune pour tous les gestionnaires de canaux
 */
public interface ChannelHandler {
    default void handleConnect() throws IOException {}
    default void handleAccept() throws IOException {}
    default void handleRead() throws IOException {}
    default void handleWrite() throws IOException {}
}
