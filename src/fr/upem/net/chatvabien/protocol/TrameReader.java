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

        System.out.println("=== TrameReader.process ===");
        System.out.println("State: " + state);
        System.out.println("Buffer position: " + bb.position() + ", remaining: " + bb.remaining());

        // ✅ CORRECTION: BOUCLE pour traiter plusieurs états dans le même appel
        while (bb.hasRemaining()) {
            switch (state) {
                case WAITING_OPCODE -> {
                    System.out.println("Lecture OPCODE...");
                    var status = opcodeReader.process(bb);
                    System.out.println("Status OPCODE: " + status);
                    if (status == ProcessStatus.DONE) {
                        opcode = OPCODE.fromCode(opcodeReader.get());
                        System.out.println("OPCODE lu: " + opcode);
                        if (opcode == null) {
                            System.out.println("OPCODE invalide!");
                            state = State.ERROR;
                            return ProcessStatus.ERROR;
                        }
                        opcodeReader.reset();
                        state = State.WAITING_SENDER;
                        System.out.println("✅ Passage à WAITING_SENDER");
                        // ✅ CORRECTION: CONTINUE la boucle au lieu de return
                    } else {
                        return status;
                    }
                }

                case WAITING_SENDER -> {
                    System.out.println("Lecture SENDER...");
                    var status = senderReader.process(bb);
                    System.out.println("Status SENDER: " + status);
                    if (status == ProcessStatus.DONE) {
                        sender = senderReader.get();
                        System.out.println("SENDER lu: " + sender);
                        senderReader.reset();
                        state = State.WAITING_MESSAGE;
                        System.out.println("✅ Passage à WAITING_MESSAGE");
                        // ✅ CORRECTION: CONTINUE la boucle au lieu de return
                    } else {
                        return status;
                    }
                }

                case WAITING_MESSAGE -> {
                    System.out.println("=== WAITING_MESSAGE ===");
                    System.out.println("OPCODE pour parsing: " + opcode);
                    System.out.println("Buffer avant parseMessage - position: " + bb.position() + ", remaining: " + bb.remaining());

                    var messageStatus = parseMessage(bb);
                    System.out.println("Retour parseMessage: " + messageStatus);

                    if (messageStatus == ProcessStatus.DONE) {
                        state = State.DONE;
                        System.out.println("✅ MESSAGE parsé avec succès!");
                        return ProcessStatus.DONE;
                    } else {
                        System.out.println("❌ parseMessage a échoué: " + messageStatus);
                        return messageStatus;
                    }
                }
            }
        }

        // Si on arrive ici, on a besoin de plus de données
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
        System.out.println("=== parsePublicMessage ===");
        System.out.println("Buffer position: " + bb.position() + ", remaining: " + bb.remaining());

        var status = messageReader.process(bb);
        System.out.println("Status messageReader: " + status);

        if (status == ProcessStatus.DONE) {
            String messageText = messageReader.get();
            System.out.println("Message lu: '" + messageText + "'");
            message = new PublicMessage(messageText);
            messageReader.reset();
            System.out.println("PublicMessage créé avec succès!");
            return ProcessStatus.DONE;
        } else {
            System.out.println("MessageReader demande REFILL");
            return status;
        }
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