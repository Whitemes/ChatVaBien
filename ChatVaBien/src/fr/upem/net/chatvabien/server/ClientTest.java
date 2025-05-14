package fr.upem.net.chatvabien.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import fr.upem.net.chatvabien.protocol.OPCODE;

public class ClientTest {
	private static void sendMessage(int version, SocketChannel sc, byte opcode, long id) throws IOException {
	    //String ip = sc.socket().getInetAddress().toString();
		var ip = sc.socket().getInetAddress().getAddress();
	    ByteBuffer bb = ByteBuffer.allocate(1 + Long.BYTES + 1 + ip.length);

	    bb.put(opcode);
	    bb.putLong(id);
	    bb.put((byte) version);
	   // bb.put(ip.getBytes(StandardCharsets.UTF_8));
	    bb.put(sc.socket().getInetAddress().getAddress());
	    
	    bb.flip();
	    sc.write(bb);
	}

    public static void main(String[] args) throws IOException {
        try (SocketChannel sc = SocketChannel.open()) {
            sc.connect(new InetSocketAddress("localhost", 7777));
            
            System.out.println("Connexion établie avec le serveur.");

            long id1 = 1111L;
            long id2 = 2222L;

            sendMessage(0x4, sc, OPCODE.LOGIN.getCode(), id1);
            pause();

            sendMessage(0x4, sc, OPCODE.LOGIN.getCode(), id1);
            pause();
            
            sendMessage(0x4, sc, OPCODE.LOGIN.getCode(), id2);
            pause();

            System.out.println("Fin des tests.");

        } catch (IOException e) {
            System.err.println("Erreur côté client: " + e.getMessage());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}