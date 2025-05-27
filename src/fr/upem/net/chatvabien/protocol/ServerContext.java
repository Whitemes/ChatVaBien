package fr.upem.net.chatvabien.protocol;

public interface ServerContext {
    void handleLogin();
    void handleGetUsers();
    void handlePrivateRequest();
    void handleOKPrivateRequest();
    void handleKOPrivateRequest();
    void broadcastMessage(String message, String sender);
}
