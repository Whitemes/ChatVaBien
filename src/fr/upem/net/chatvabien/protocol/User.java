package fr.upem.net.chatvabien.protocol;

import java.nio.channels.SocketChannel;

public record User(String pseudo, SocketChannel sc, boolean isAuth) {

}
