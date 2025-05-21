package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record KOPrivateResquest(String peusdoRequester, String peusdoTarget) implements Request {

	@Override
	public void handle(Context context) {
		context.handleKOPrivateRequest();
	}

}
