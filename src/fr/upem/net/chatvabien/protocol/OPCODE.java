package fr.upem.net.chatvabien.protocol;

public enum OPCODE {
    LOGIN((byte) 11),
    LOGINAUTH((byte) 1),
    LOGIN_ACCEPTED((byte) 2),
    LOGIN_REFUSED((byte) 3),
    MESSAGE((byte) 4),
    REQUEST_PRIVATE((byte) 5),
    OK_PRIVATE((byte) 6),
    KO_PRIVATE((byte) 7),
    OPEN((byte) 8),
    FILE((byte) 9),
    NOPE((byte) 10);

    private final byte code;

    OPCODE(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static OPCODE fromCode(byte code) {
        for (OPCODE op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        return null;
    }
}
