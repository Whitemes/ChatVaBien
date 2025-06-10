package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

public class MessageReader implements Reader<Message> {
    private enum State { WAITING_LOGIN, WAITING_TEXT, DONE, ERROR }

    private State state = State.WAITING_LOGIN;
    private final StringReader stringReader = new StringReader();
    private String login;
    private String text;
    private Message message;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        Objects.requireNonNull(bb);

        switch (state) {
            case WAITING_LOGIN:
                ProcessStatus statusLogin = stringReader.process(bb);
                if (statusLogin == ProcessStatus.DONE) {
                    login = stringReader.get();
                    stringReader.reset();
                    state = State.WAITING_TEXT;
                } else {
                    return statusLogin;
                }
            case WAITING_TEXT:
                ProcessStatus statusText = stringReader.process(bb);
                if (statusText == ProcessStatus.DONE) {
                    text = stringReader.get();
                    stringReader.reset();
                    message = new Message(login, text);
                    state = State.DONE;
                    return ProcessStatus.DONE;
                } else {
                    return statusText;
                }
            case DONE:
                return ProcessStatus.DONE;
            default:
                return ProcessStatus.ERROR;
        }
    }

    @Override
    public Message get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return message;
    }

    @Override
    public void reset() {
        state = State.WAITING_LOGIN;
        stringReader.reset();
        login = null;
        text = null;
        message = null;
    }
}
