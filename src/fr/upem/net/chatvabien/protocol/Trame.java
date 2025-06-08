package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record Trame(byte opcode, String peusdo, Request request) {
	
	private static final Charset UTF8 = StandardCharsets.UTF_8;
	
	public ByteBuffer toByteBuffer() {
        ByteBuffer loginEncoded = UTF8.encode(peusdo);
        ByteBuffer requestBuffer = request.toByteBuffer();
        
        int totalSize = Byte.BYTES + Integer.BYTES + loginEncoded.remaining() + requestBuffer.remaining();
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.clear();
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        buffer.put(opcode);
        
        buffer.putInt(loginEncoded.remaining());
        buffer.put(loginEncoded);
	    buffer.put(requestBuffer);
        
        buffer.flip();
        
        return buffer;
    }

}
