package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record MessageRequest(String peusdo, String message) implements Request {

	@Override
    public void handle(Context context) {
        context.broadcastMessage(peusdo, message);
    }

}
