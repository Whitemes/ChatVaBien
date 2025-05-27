package fr.upem.net.chatvabien.protocol;

public record PrivateRequest(String peusdoRequester, String peusdoTarget) implements Request {

	@Override
	public void handle(ServerContext context) {
		context.handlePrivateRequest();
	}
	
}
