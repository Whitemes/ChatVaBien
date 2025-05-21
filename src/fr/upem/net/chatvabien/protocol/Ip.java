package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;

public record Ip(byte version, InetAddress ip) {

}
