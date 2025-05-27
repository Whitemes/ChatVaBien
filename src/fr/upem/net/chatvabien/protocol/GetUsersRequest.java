package fr.upem.net.chatvabien.protocol;

public record GetUsersRequest() implements Request {
	@Override
	public void handle(ServerContext context) {
		context.handleGetUsers();
	}
}