package fr.upem.net.chatvabien.client;

/**
 * Interface pour traiter les commandes utilisateur
 */
public interface CommandProcessor {
    void processCommand(String command);
    void showHelp();
}
