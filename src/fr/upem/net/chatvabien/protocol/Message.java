package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record Message(String peusdo, String text) implements Request  {

	private static final Charset UTF8 = StandardCharsets.UTF_8;

    public ByteBuffer toByteBuffer() {
        ByteBuffer textEncoded = UTF8.encode(text);
        
        int totalSize = Integer.BYTES + textEncoded.remaining();
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.clear();
        buffer.order(ByteOrder.BIG_ENDIAN);
                
	    buffer.putInt(textEncoded.remaining());
	    buffer.put(textEncoded);
        
        buffer.flip();
        
        return buffer;
    }

	@Override
	public void handle(ServerContext context) {
		// TODO Auto-generated method stub
		
	}

}
