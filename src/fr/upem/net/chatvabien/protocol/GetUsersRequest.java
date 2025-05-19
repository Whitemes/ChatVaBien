package fr.upem.net.chatvabien.protocol;

import fr.upem.net.chatvabien.server.ChatVaBienServer.Context;

public record GetUsersRequest() implements Request {

	@Override
	public void handle(Context context) {
		context.handleGetUsers();
		
	}

}
