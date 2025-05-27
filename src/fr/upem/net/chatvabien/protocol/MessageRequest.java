package fr.upem.net.chatvabien.protocol;

public record MessageRequest(String peusdo, String message) implements Request {

	@Override
    public void handle(ServerContext context) {
        context.broadcastMessage(peusdo, message);
    }

}
