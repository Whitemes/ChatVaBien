package fr.upem.net.chatvabien.protocol;

public record KOPrivateResquest(String peusdoRequester, String peusdoTarget) implements Request {

	@Override
	public void handle(ServerContext context) {
		context.handleKOPrivateRequest();
	}

}
