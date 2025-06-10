package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * Reader unifié pour lire les trames complètes
 */
public class TrameReader implements Reader<Trame> {

    private enum State {
        WAITING_OPCODE, WAITING_SENDER, WAITING_MESSAGE, DONE, ERROR
    }

    private State state = State.WAITING_OPCODE;
    private final ByteReader opcodeReader = new ByteReader();
    private final StringReader senderReader = new StringReader();
    private final StringReader messageReader = new StringReader(); // Réutilisable pour tous les messages

    private OPCODE opcode;
    private String sender;
    private Message message;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }

        switch (state) {
            case WAITING_OPCODE -> {
                var status = opcodeReader.process(bb);
                if (status == ProcessStatus.DONE) {
                    opcode = OPCODE.fromCode(opcodeReader.get());
                    if (opcode == null) {
                        state = State.ERROR;
                        return ProcessStatus.ERROR;
                    }
                    opcodeReader.reset();
                    state = State.WAITING_SENDER;
                } else {
                    return status;
                }
            }

            case WAITING_SENDER -> {
                var status = senderReader.process(bb);
                if (status == ProcessStatus.DONE) {
                    sender = senderReader.get();
                    senderReader.reset();
                    state = State.WAITING_MESSAGE;
                } else {
                    return status;
                }
            }

            case WAITING_MESSAGE -> {
                var messageStatus = parseMessage(bb);
                if (messageStatus == ProcessStatus.DONE) {
                    state = State.DONE;
                    return ProcessStatus.DONE;
                } else {
                    return messageStatus;
                }
            }
        }

        return ProcessStatus.REFILL;
    }

    private ProcessStatus parseMessage(ByteBuffer bb) {
        // Parse selon l'opcode
        return switch (opcode) {
            case LOGIN -> {
                message = new LoginMessage();
                yield ProcessStatus.DONE;
            }
            case MESSAGE -> parsePublicMessage(bb);
            case REQUEST_PRIVATE -> parsePrivateRequest(bb);
            case OK_PRIVATE -> parseOKPrivate(bb);
            case KO_PRIVATE -> parseKOPrivate(bb);
            case GET_CONNECTED_USERS -> {
                message = new GetUsersMessage();
                yield ProcessStatus.DONE;
            }
            default -> ProcessStatus.ERROR;
        };
    }

    private ProcessStatus parsePublicMessage(ByteBuffer bb) {
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            message = new PublicMessage(messageReader.get());
            messageReader.reset();
        }
        return status;
    }

    private ProcessStatus parsePrivateRequest(ByteBuffer bb) {
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            message = new PrivateRequestMessage(messageReader.get());
            messageReader.reset();
        }
        return status;
    }

    private ProcessStatus parseOKPrivate(ByteBuffer bb) {
        // Pour l'instant, juste le target pseudo - version simplifiée
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            String targetPseudo = messageReader.get();
            messageReader.reset();
            // Version simplifiée sans IP/token pour le test
            message = new OKPrivateMessage(targetPseudo, null, -1);
        }
        return status;
    }

    private ProcessStatus parseKOPrivate(ByteBuffer bb) {
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            message = new KOPrivateMessage(messageReader.get());
            messageReader.reset();
        }
        return status;
    }

    @Override
    public Trame get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return new Trame(opcode, sender, message);
    }

    @Override
    public void reset() {
        state = State.WAITING_OPCODE;
        opcodeReader.reset();
        senderReader.reset();
        messageReader.reset();
        opcode = null;
        sender = null;
        message = null;
    }
}