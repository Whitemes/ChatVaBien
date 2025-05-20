package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record PrivateRequest() implements Request {

	@Override
	public void handle(Context context) {
		context.handlePrivateRequest();
	}

}
