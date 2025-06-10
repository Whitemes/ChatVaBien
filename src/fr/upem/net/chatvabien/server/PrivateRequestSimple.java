package fr.upem.net.chatvabien.server;

/**
 * Classe temporaire pour que ClientTest compile
 * TODO: Remplacer par la nouvelle architecture
 */
public record PrivateRequestSimple(String peusdoRequester, String peusdoTarget) {

    public String peusdoRequester() {
        return peusdoRequester;
    }

    public String peusdoTarget() {
        return peusdoTarget;
    }
}