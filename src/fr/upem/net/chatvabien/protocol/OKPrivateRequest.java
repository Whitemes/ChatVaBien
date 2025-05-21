package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record OKPrivateRequest(String peusdoRequester, String peusdoTarget, long token) implements Request {

	@Override
	public void handle(Context context) {
		context.handleOKPrivateRequest();
		
	}

}
