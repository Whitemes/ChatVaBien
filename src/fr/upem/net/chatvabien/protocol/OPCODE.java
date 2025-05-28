package fr.upem.net.chatvabien.protocol;

/**
 * Enumerates the possible operation codes (OPCODE) used in the ChatVaBien protocol.
 * <p>
 * Each OPCODE corresponds to a specific type of request or message exchanged between client and server.
 */
public enum OPCODE {
    /**
     * Login request opcode.
     */
    LOGIN((byte) 0x00),
    /**
     * Login authentication (credentials) opcode.
     */
    LOGINAUTH((byte) 0x01),
    /**
     * Login accepted response opcode.
     */
    LOGIN_ACCEPTED((byte) 0x02),
    /**
     * Login refused response opcode.
     */
    LOGIN_REFUSED((byte) 0x03),
    /**
     * Public message opcode.
     */
    MESSAGE((byte) 0x04),
    /**
     * Private connection request opcode.
     */
    REQUEST_PRIVATE((byte) 0x05),
    /**
     * Private connection accepted opcode.
     */
    OK_PRIVATE((byte) 0x06),
    /**
     * Private connection refused opcode.
     */
    KO_PRIVATE((byte) 0x07),
    /**
     * File transfer initiation opcode.
     */
    OPEN((byte) 0x08),
    /**
     * File data transfer opcode.
     */
    FILE((byte) 0x09),
    /**
     * General refusal or "not allowed" opcode.
     */
    NOPE((byte) 0x10),
    /**
     * Request to get the list of connected users opcode.
     */
    GET_CONNECTED_USERS((byte) 0x11),
    /**
     * Response containing the list of connected users opcode.
     */
    CONNECTED_USERS_LIST((byte) 0x12);

    private final byte code;

    /**
     * Constructs an OPCODE with the given byte code.
     *
     * @param code the byte value representing the OPCODE
     */
    OPCODE(byte code) {
        this.code = code;
    }

    /**
     * Returns the byte code associated with this OPCODE.
     *
     * @return the byte code
     */
    public byte getCode() {
        return code;
    }

    /**
     * Retrieves the OPCODE corresponding to the given byte code.
     *
     * @param code the byte code to look up
     * @return the matching {@link OPCODE}, or {@code null} if not found
     */
    public static OPCODE fromCode(byte code) {
        for (OPCODE op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        return null;
    }
}
