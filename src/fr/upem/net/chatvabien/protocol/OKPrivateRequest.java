package fr.upem.net.chatvabien.protocol;

public record OKPrivateRequest(String peusdoRequester, String peusdoTarget, long token) implements Request {

	@Override
	public void handle(ServerContext context) {
		context.handleOKPrivateRequest();
		
	}

}
