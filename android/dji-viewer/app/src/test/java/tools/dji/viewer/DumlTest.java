package tools.dji.viewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class DumlTest {
    @Test
    public void dm368RegistrationMatchesReferencePacket() {
        byte[] packet = Duml.build(2, 0, 8, 2, 0, 0x82, new byte[] {0}, 0x1230, Duml.TYPE_WRITE);
        assertEquals("550e046602483012800082000bb4", hex(packet));
    }

    @Test
    public void actionIdrRequestMatchesReferencePacket() {
        byte[] payload = new byte[] {0, 4, 2, 0, 0, 0, 0, 0, 0, 0};
        byte[] packet = Duml.build(2, 0, 1, 2, 9, 0xa8, payload, 0x1238, Duml.TYPE_REQUEST);
        assertEquals("55170438024138120009a800040200000000000000cedf", hex(packet));
    }

    @Test
    public void depacketizerSortsNonZeroBasedFragmentsAndEmitsCleanIdr() {
        byte[] accessUnit = new byte[] {
                0, 0, 1, (byte) 0xff, 0x55,
                0, 0, 1, 0x40, 0x01, 0x11,
                0, 0, 1, 0x26, 0x01, 0x22, 0x33,
        };
        List<byte[]> emitted = new ArrayList<>();
        LiveViewDepacketizer depacketizer = new LiveViewDepacketizer(new LiveViewDepacketizer.Listener() {
            @Override public void onAccessUnit(byte[] frame) { emitted.add(frame); }
            @Override public void onNeedsRandomAccess() {}
            @Override public void onStats(LiveViewDepacketizer.Stats stats) {}
        });

        byte[] first = fragment(7, 2, 64, slice(accessUnit, 0, 9));
        byte[] second = fragment(7, 2, 65, slice(accessUnit, 9, accessUnit.length));
        depacketizer.feed(second, second.length);
        depacketizer.feed(first, first.length);

        assertEquals(1, emitted.size());
        assertArrayEquals(slice(accessUnit, 5, accessUnit.length), emitted.get(0));
    }

    private static byte[] fragment(int frame, int count, int position, byte[] data) {
        byte[] result = new byte[12 + data.length];
        result[8] = (byte) frame;
        result[9] = (byte) (count | ((position & 1) << 7));
        result[10] = (byte) (position / 2);
        System.arraycopy(data, 0, result, 12, data.length);
        return result;
    }

    private static byte[] slice(byte[] source, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(source, start, result, 0, result.length);
        return result;
    }

    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
