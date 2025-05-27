package fr.upem.net.chatvabien.protocol;

public sealed interface Request
        permits LoginRequest, MessageRequest, GetUsersRequest, PrivateRequest, OKPrivateRequest, KOPrivateResquest {
    void handle(ServerContext context);
}
