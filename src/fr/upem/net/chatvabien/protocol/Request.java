package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer;

public sealed interface Request permits LoginRequest, MessageRequest {
    void handle(ChatVaBienServer.Context context);
}