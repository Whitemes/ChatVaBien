package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record Message(String login, String text) {

	private static final Charset UTF8 = StandardCharsets.UTF_8;

    public ByteBuffer toByteBuffer() {
        ByteBuffer loginEncoded = UTF8.encode(login);
        ByteBuffer textEncoded = UTF8.encode(text);
        
        int totalSize = 4 + loginEncoded.remaining() + 4 + textEncoded.remaining();
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        buffer.putInt(loginEncoded.remaining());
        buffer.put(loginEncoded);
        
        buffer.putInt(textEncoded.remaining());
        buffer.put(textEncoded);
        
        buffer.flip();
        
        return buffer;
    }

}
