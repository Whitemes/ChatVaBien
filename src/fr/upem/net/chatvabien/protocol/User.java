package fr.upem.net.chatvabien.protocol;

import java.nio.channels.SocketChannel;

public record User(long id, String pseudo, SocketChannel sc, boolean isAuth) {

}
