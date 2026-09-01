package tools.dji.viewer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

final class DjiLanDiscovery {
    static final class Result implements AutoCloseable {
        final String cameraIp;
        final DatagramSocket socket;
        final int sessionId;
        final int seed;

        Result(String cameraIp, DatagramSocket socket, int sessionId, int seed) {
            this.cameraIp = cameraIp;
            this.socket = socket;
            this.sessionId = sessionId;
            this.seed = seed;
        }

        @Override public void close() { socket.close(); }
    }

    private DjiLanDiscovery() {}

    static Result discover(Context context, Network network, String cachedIp, long timeoutMs) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = manager.getLinkProperties(network);
        Inet4Address phoneAddress = null;
        if (properties != null) {
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    phoneAddress = (Inet4Address) address.getAddress();
                    break;
                }
            }
        }
        if (phoneAddress == null) throw new IOException("Wi-Fi has no IPv4 address");

        SecureRandom random = new SecureRandom();
        int session = random.nextInt(0x10000);
        int seed = random.nextInt(0x10000) & 0xfff8;
        byte[] probe = handshake(session, seed);
        byte[] local = phoneAddress.getAddress();

        DatagramSocket socket = new DatagramSocket();
        boolean handedOff = false;
        try {
            network.bindSocket(socket);
            socket.setReceiveBufferSize(256 * 1024);
            if (cachedIp != null && !cachedIp.isEmpty()) send(socket, probe, InetAddress.getByName(cachedIp));
            for (int host = 1; host < 255; host++) {
                if ((local[3] & 0xff) == host) continue;
                byte[] candidate = new byte[] {local[0], local[1], local[2], (byte) host};
                String candidateText = InetAddress.getByAddress(candidate).getHostAddress();
                if (candidateText.equals(cachedIp)) continue;
                send(socket, probe, InetAddress.getByAddress(candidate));
            }

            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            byte[] response = new byte[4096];
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                int remaining = (int) Math.max(1, deadline - android.os.SystemClock.elapsedRealtime());
                socket.setSoTimeout(Math.min(remaining, 500));
                DatagramPacket datagram = new DatagramPacket(response, response.length);
                try {
                    socket.receive(datagram);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                if (validHandshake(response, datagram.getLength())) {
                    handedOff = true;
                    return new Result(
                            datagram.getAddress().getHostAddress(),
                            socket,
                            Duml.u16(response, 2),
                            seed);
                }
            }
        } finally {
            if (!handedOff) socket.close();
        }
        throw new IOException("OA4 did not answer UDP discovery on the Wi-Fi subnet");
    }

    private static void send(DatagramSocket socket, byte[] probe, InetAddress address) {
        try { socket.send(new DatagramPacket(probe, probe.length, address, 9004)); } catch (IOException ignored) {}
    }

    private static byte[] handshake(int session, int seed) {
        byte[] payload = new byte[] {
                (byte) seed, (byte) (seed >>> 8),
                0x64, 0x00, 0x64, 0x00, (byte) 0xc0, 0x05,
                0x14, 0x00, 0x00, 0x64, 0x00, 0x00, 0x01, (byte) 0x90,
                0x01, (byte) 0xc0, 0x05, 0x14, 0x00, 0x00, 0x64, 0x00,
                0x14, 0x00, 0x64, 0x00, (byte) 0xc0, 0x05, 0x14, 0x00,
                0x00, 0x64, 0x00, 0x01, 0x01, 0x04, 0x01, 0x02,
        };
        byte[] packet = new byte[8 + payload.length];
        Duml.putU16(packet, 0, packet.length | 0x8000);
        Duml.putU16(packet, 2, session);
        packet[6] = 0;
        int xor = 0;
        for (int index = 0; index < 7; index++) xor ^= packet[index] & 0xff;
        packet[7] = (byte) xor;
        System.arraycopy(payload, 0, packet, 8, payload.length);
        return packet;
    }

    private static boolean validHandshake(byte[] packet, int length) {
        if (length < 8 || (packet[6] & 0xff) != 0) return false;
        int declared = Duml.u16(packet, 0) & 0x7fff;
        if (declared != length) return false;
        int xor = 0;
        for (int index = 0; index < 7; index++) xor ^= packet[index] & 0xff;
        return xor == (packet[7] & 0xff);
    }
}
