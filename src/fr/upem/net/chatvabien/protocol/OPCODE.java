package fr.upem.net.chatvabien.protocol;

public enum OPCODE {
    LOGIN((byte) 0x00),
    LOGINAUTH((byte) 0x01),
    LOGIN_ACCEPTED((byte) 0x02),
    LOGIN_REFUSED((byte) 0x03),
    MESSAGE((byte) 0x04),
    REQUEST_PRIVATE((byte) 0x05),
    OK_PRIVATE((byte) 0x06),
    KO_PRIVATE((byte) 0x07),
    OPEN((byte) 0x08),
    FILE((byte) 0x09),
    NOPE((byte) 0x10),
	GET_CONNECTED_USERS((byte) 0x11),
	CONNECTED_USERS_LIST((byte) 0x12);
	
	

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
