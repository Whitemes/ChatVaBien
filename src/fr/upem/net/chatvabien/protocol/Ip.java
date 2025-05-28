package fr.upem.net.chatvabien.protocol;

import java.net.InetAddress;

/**
 * Represents an IP endpoint consisting of an IP version, an address, and a port.
 *
 * @param version the IP version (e.g., 4 for IPv4, 6 for IPv6)
 * @param address the IP address
 * @param port    the port number
 */
public record Ip(byte version, InetAddress address, int port) {

    /**
     * Returns a string representation of this IP endpoint.
     *
     * @return a string in the format "address:port (version=version)"
     */
    @Override
    public String toString() {
        return address.getHostAddress() + ":" + port + " (version=" + version + ")";
    }
}
