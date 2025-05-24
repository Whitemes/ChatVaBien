package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;

public record Ip(byte version, InetAddress address, int port) {
	
	@Override
    public String toString() {
        return address.getHostAddress() + ":" + port + " (version=" + version + ")";
    }
	
}
