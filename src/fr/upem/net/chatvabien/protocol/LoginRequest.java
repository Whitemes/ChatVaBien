package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record LoginRequest(long id) implements Request {

	@Override
	public void handle(Context context) {
		context.handleLogin(id);
	}
}