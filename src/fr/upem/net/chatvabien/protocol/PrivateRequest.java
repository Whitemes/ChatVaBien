package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record PrivateRequest(String peusdo_requester, String peusdo_target) implements Request {

	@Override
	public void handle(Context context) {
		context.handlePrivateRequest();
	}

}
