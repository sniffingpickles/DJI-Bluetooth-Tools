package tools.dji.viewer;

final class Duml {
    static final int TYPE_REQUEST = 0;
    static final int TYPE_PUSH = 2;
    static final int TYPE_WRITE = 4;

    private Duml() {}

    static byte[] build(
            int senderType,
            int senderId,
            int receiverType,
            int receiverId,
            int commandSet,
            int commandId,
            byte[] payload,
            int sequence,
            int commandType) {
        int length = 13 + payload.length;
        byte[] packet = new byte[length];
        packet[0] = 0x55;
        int lengthAndVersion = (length & 0x3ff) | (1 << 10);
        putU16(packet, 1, lengthAndVersion);
        packet[3] = (byte) crc8(packet, 0, 3);
        packet[4] = (byte) ((senderId << 5) | (senderType & 0x1f));
        packet[5] = (byte) ((receiverId << 5) | (receiverType & 0x1f));
        putU16(packet, 6, sequence);
        packet[8] = (byte) ((commandType & 7) << 5);
        packet[9] = (byte) commandSet;
        packet[10] = (byte) commandId;
        System.arraycopy(payload, 0, packet, 11, payload.length);
        putU16(packet, length - 2, crc16(packet, 0, length - 2));
        return packet;
    }

    static int crc8(byte[] data, int offset, int length) {
        int crc = 0x77;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? ((crc >>> 1) ^ 0x8c) : (crc >>> 1);
            }
        }
        return crc & 0xff;
    }

    static int crc16(byte[] data, int offset, int length) {
        int crc = 0x3692;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? ((crc >>> 1) ^ 0x8408) : (crc >>> 1);
            }
        }
        return crc & 0xffff;
    }

    static void putU16(byte[] destination, int offset, int value) {
        destination[offset] = (byte) value;
        destination[offset + 1] = (byte) (value >>> 8);
    }

    static int u16(byte[] source, int offset) {
        return (source[offset] & 0xff) | ((source[offset + 1] & 0xff) << 8);
    }
}
