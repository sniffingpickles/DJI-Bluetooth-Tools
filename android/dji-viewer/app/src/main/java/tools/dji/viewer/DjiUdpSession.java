package tools.dji.viewer;

import android.net.Network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

final class DjiUdpSession implements AutoCloseable {
    interface Listener {
        void onStatus(String status);
        void onVideoPacket(byte[] payload, int length);
        void onError(String error);
    }

    private static final int PORT = 9004;
    private static final int TYPE_HANDSHAKE = 0;
    private static final int TYPE_TELEMETRY = 1;
    private static final int TYPE_VIDEO = 2;
    private static final int TYPE_ACK_TELEMETRY = 3;
    private static final int TYPE_ACK = 4;
    private static final int TYPE_COMMAND = 5;

    private static final byte[] IDR_PAYLOAD = new byte[] {
            0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private final String cameraIp;
    private final Network network;
    private final Listener listener;
    private final DjiLanDiscovery.Result discoveredSession;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket socket;
    private InetAddress cameraAddress;
    private int sessionId;
    private int commandSequence;
    private int messageSequence = 1;
    private volatile int videoRxSequence;
    private volatile int telemetryRxSequence;
    private volatile int extraRxSequence;
    private Thread receiverThread;
    private Thread ackThread;
    private Thread heartbeatThread;
    private int heartbeatCounter;
    private long packets;
    private long videoPackets;
    private volatile long lastIdrRequestMs;

    DjiUdpSession(String cameraIp, Network network, Listener listener) {
        this.cameraIp = cameraIp;
        this.network = network;
        this.listener = listener;
        this.discoveredSession = null;
    }

    DjiUdpSession(DjiLanDiscovery.Result discoveredSession, Network network, Listener listener) {
        this.cameraIp = discoveredSession.cameraIp;
        this.network = network;
        this.listener = listener;
        this.discoveredSession = discoveredSession;
    }

    void connectAndStart() throws IOException {
        listener.onStatus("Handshaking with " + cameraIp + ":9004…");
        cameraAddress = InetAddress.getByName(cameraIp);
        if (discoveredSession != null) {
            socket = discoveredSession.socket;
            sessionId = discoveredSession.sessionId;
            commandSequence = discoveredSession.seed;
            videoRxSequence = discoveredSession.seed;
            telemetryRxSequence = discoveredSession.seed;
            extraRxSequence = discoveredSession.seed;
        } else {
            socket = new DatagramSocket();
            if (network != null) network.bindSocket(socket);
            socket.setReceiveBufferSize(4 * 1024 * 1024);
            socket.setSoTimeout(5_000);

            SecureRandom random = new SecureRandom();
            sessionId = random.nextInt(0x10000);
            int seed = random.nextInt(0x10000) & 0xfff8;
            commandSequence = seed;
            videoRxSequence = seed;
            telemetryRxSequence = seed;
            extraRxSequence = seed;

            byte[] handshakePayload = new byte[] {
                    (byte) seed, (byte) (seed >>> 8),
                    0x64, 0x00, 0x64, 0x00, (byte) 0xc0, 0x05,
                    0x14, 0x00, 0x00, 0x64, 0x00, 0x00, 0x01, (byte) 0x90,
                    0x01, (byte) 0xc0, 0x05, 0x14, 0x00, 0x00, 0x64, 0x00,
                    0x14, 0x00, 0x64, 0x00, (byte) 0xc0, 0x05, 0x14, 0x00,
                    0x00, 0x64, 0x00, 0x01, 0x01, 0x04, 0x01, 0x02,
            };
            sendPacket(TYPE_HANDSHAKE, 0, handshakePayload);

            byte[] responseBuffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            if (response.getLength() < 8 || !validHeader(responseBuffer, response.getLength()) || (responseBuffer[6] & 0xff) != TYPE_HANDSHAKE) {
                throw new IOException("Camera returned an invalid handshake response");
            }
            sessionId = Duml.u16(responseBuffer, 2);
        }
        socket.setReceiveBufferSize(4 * 1024 * 1024);
        socket.setSoTimeout(500);
        running.set(true);

        receiverThread = new Thread(this::receiveLoop, "oa4-udp-receiver");
        ackThread = new Thread(this::ackLoop, "oa4-udp-acks");
        heartbeatThread = new Thread(this::heartbeatLoop, "oa4-video-heartbeat");
        receiverThread.start();
        ackThread.start();

        startVideo();
        heartbeatThread.start();
        listener.onStatus("Connected — requesting clean HEVC picture…");
    }

    void requestIFrame() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!running.get() || now - lastIdrRequestMs < 1_500) return;
        lastIdrRequestMs = now;
        sendDuml(1, 2, 0x09, 0xa8, IDR_PAYLOAD, Duml.TYPE_REQUEST);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65_535];
        while (running.get()) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (IOException error) {
                if (running.get()) listener.onError("UDP receive failed: " + error.getMessage());
                break;
            }
            int length = datagram.getLength();
            if (!validHeader(buffer, length)) continue;
            packets++;
            int type = buffer[6] & 0xff;
            int sequence = Duml.u16(buffer, 4);
            int payloadLength = length - 8;
            if (type == TYPE_VIDEO) {
                videoPackets++;
                videoRxSequence = sequence & 0xfff8;
                byte[] payload = Arrays.copyOfRange(buffer, 8, length);
                listener.onVideoPacket(payload, payloadLength);
                if (videoPackets == 3) requestIFrame();
            } else if (type == TYPE_TELEMETRY && payloadLength >= 20) {
                telemetryRxSequence = Duml.u16(buffer, 8 + 10);
                extraRxSequence = Duml.u16(buffer, 8 + 18);
            } else if (type == TYPE_ACK_TELEMETRY) {
                if (sequenceAhead(sequence, telemetryRxSequence)) telemetryRxSequence = sequence;
            }
        }
    }

    private void ackLoop() {
        while (running.get()) {
            byte[] payload = new byte[26];
            Duml.putU16(payload, 0, videoRxSequence);
            Duml.putU16(payload, 2, videoRxSequence);
            Duml.putU16(payload, 8, telemetryRxSequence);
            Duml.putU16(payload, 10, telemetryRxSequence);
            Duml.putU16(payload, 16, extraRxSequence);
            Duml.putU16(payload, 18, extraRxSequence);
            try { sendPacket(TYPE_ACK, 0, payload); } catch (IOException ignored) {}
            sleep(20);
        }
    }

    private void startVideo() throws IOException {
        sendDuml(8, 1, 0x00, 0x88, new byte[] {
                0x17, 0x00, 0x46, 0x23, 0x73, 0x41, 0x50, 0x50,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
        }, Duml.TYPE_PUSH);
        registerVideoClient();
    }

    private void registerVideoClient() {
        byte[] identity = new byte[64];
        identity[1] = 'A';
        identity[2] = 'P';
        identity[3] = 'P';
        sendDuml(8, 2, 0x00, 0x81, identity, Duml.TYPE_WRITE);
        sendDuml(8, 2, 0x00, 0x82, new byte[] {0}, Duml.TYPE_WRITE);
    }

    private void heartbeatLoop() {
        int tick = 0;
        while (running.get()) {
            byte[] heartbeat = new byte[] {
                    0x01, 0x00, (byte) heartbeatCounter, 0x00, 0x00,
                    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            };
            sendDuml(8, 2, 0x00, 0x4f, heartbeat, Duml.TYPE_PUSH);
            if ((tick & 1) == 1) heartbeatCounter++;
            if (tick > 0 && tick % 10 == 0) registerVideoClient();
            if (tick > 0 && tick % 25 == 0) {
                sendDuml(8, 1, 0x00, 0x88, new byte[] {
                        0x17, 0x00, 0x68, 0x23, 0x69, 0x41, 0x50, 0x50,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
                }, Duml.TYPE_PUSH);
            }
            tick++;
            sleep(200);
        }
    }

    private synchronized void sendDuml(
            int receiverType,
            int receiverId,
            int commandSet,
            int commandId,
            byte[] payload,
            int commandType) {
        if (socket == null || socket.isClosed()) return;
        int sequence = commandSequence;
        commandSequence = (commandSequence + 8) & 0xffff;
        byte[] duml = Duml.build(2, 0, receiverType, receiverId, commandSet, commandId, payload, sequence, commandType);
        byte[] body = new byte[12 + duml.length];
        Duml.putU16(body, 0, (sequence - 32) & 0xffff);
        Duml.putU16(body, 2, sequence);
        body[8] = (byte) messageSequence++;
        body[9] = 0x01;
        Duml.putU16(body, 10, 0x0060);
        System.arraycopy(duml, 0, body, 12, duml.length);
        try { sendPacket(TYPE_COMMAND, sequence, body); } catch (IOException error) {
            if (running.get()) listener.onError("Command send failed: " + error.getMessage());
        }
    }

    private synchronized void sendPacket(int type, int sequence, byte[] payload) throws IOException {
        byte[] packet = new byte[8 + payload.length];
        int encodedLength = packet.length | 0x8000;
        Duml.putU16(packet, 0, encodedLength);
        Duml.putU16(packet, 2, sessionId);
        Duml.putU16(packet, 4, sequence);
        packet[6] = (byte) type;
        int xor = 0;
        for (int i = 0; i < 7; i++) xor ^= packet[i] & 0xff;
        packet[7] = (byte) xor;
        System.arraycopy(payload, 0, packet, 8, payload.length);
        DatagramPacket datagram = new DatagramPacket(packet, packet.length, cameraAddress, PORT);
        socket.send(datagram);
    }

    private static boolean validHeader(byte[] packet, int length) {
        if (length < 8) return false;
        int declared = Duml.u16(packet, 0) & 0x7fff;
        if (declared != length) return false;
        int xor = 0;
        for (int i = 0; i < 7; i++) xor ^= packet[i] & 0xff;
        return (packet[7] & 0xff) == xor;
    }

    private static boolean sequenceAhead(int newer, int older) {
        int difference = (newer - older) & 0xffff;
        return difference > 0 && difference < 0x8000;
    }

    private static void sleep(long milliseconds) {
        try { Thread.sleep(milliseconds); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    long getVideoPackets() { return videoPackets; }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            if (socket != null) socket.close();
            return;
        }
        if (socket != null) socket.close();
        if (receiverThread != null) receiverThread.interrupt();
        if (ackThread != null) ackThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        join(receiverThread);
        join(ackThread);
        join(heartbeatThread);
        listener.onStatus("Disconnected");
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
