package fr.upem.net.chatvabien.protocol;

public record LoginRequest() implements Request {
	@Override
	public void handle(ServerContext context) {
		context.handleLogin();
	}
}