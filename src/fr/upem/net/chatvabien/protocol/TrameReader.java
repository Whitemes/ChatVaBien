package fr.upem.net.chatvabien.protocol;

import java.nio.ByteBuffer;

/**
 * Reader unifié pour lire les trames complètes
 * VERSION PROPRE - Debug retiré
 */
public class TrameReader implements Reader<Trame> {

    private enum State {
        WAITING_OPCODE, WAITING_SENDER, WAITING_MESSAGE, DONE, ERROR
    }

    private State state = State.WAITING_OPCODE;
    private final ByteReader opcodeReader = new ByteReader();
    private final StringReader senderReader = new StringReader();
    private final StringReader messageReader = new StringReader();

    private OPCODE opcode;
    private String sender;
    private Message message;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }

        // Boucle pour traiter plusieurs états dans le même appel
        while (bb.hasRemaining()) {
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
        }

        // Si on arrive ici, on a besoin de plus de données
        return ProcessStatus.REFILL;
    }

    private ProcessStatus parseMessage(ByteBuffer bb) {
        return switch (opcode) {
            case LOGIN -> {
                message = new LoginMessage();
                yield ProcessStatus.DONE;
            }
            case LOGIN_ACCEPTED -> {
                message = new LoginMessage();
                yield ProcessStatus.DONE;
            }
            case LOGIN_REFUSED -> {
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
            case CONNECTED_USERS_LIST -> parsePublicMessage(bb);
            default -> ProcessStatus.ERROR;
        };
    }

    private ProcessStatus parsePublicMessage(ByteBuffer bb) {
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            String messageText = messageReader.get();
            message = new PublicMessage(messageText);
            messageReader.reset();
            return ProcessStatus.DONE;
        }
        return status;
    }

    private ProcessStatus parsePrivateRequest(ByteBuffer bb) {
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            // ✅ CORRIGÉ: Le target est dans le message, pas le sender
            String targetPseudo = messageReader.get();
            message = new PrivateRequestMessage(targetPseudo);
            messageReader.reset();
        }
        return status;
    }

    private ProcessStatus parseOKPrivate(ByteBuffer bb) {
        // ✅ VERSION SIMPLIFIÉE: Juste le target pseudo comme PublicMessage
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            String targetPseudo = messageReader.get();
            messageReader.reset();
            // ✅ TEMPORAIRE: Utiliser PublicMessage pour éviter les NullPointerException
            message = new PublicMessage(targetPseudo);
        }
        return status;
    }

    private ProcessStatus parseKOPrivate(ByteBuffer bb) {
        // ✅ VERSION SIMPLIFIÉE: Juste le target pseudo comme PublicMessage
        var status = messageReader.process(bb);
        if (status == ProcessStatus.DONE) {
            String targetPseudo = messageReader.get();
            messageReader.reset();
            // ✅ TEMPORAIRE: Utiliser PublicMessage pour éviter les problèmes
            message = new PublicMessage(targetPseudo);
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