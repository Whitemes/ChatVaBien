package fr.upem.net.chatvabien.protocol;

import java.nio.channels.SocketChannel;

public record UserPrivate(String peusdo, SocketChannel sc) {

}
