package tools.dji.viewer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

final class LiveViewDepacketizer {
    interface Listener {
        void onAccessUnit(byte[] accessUnit);
        void onNeedsRandomAccess();
        void onStats(Stats stats);
    }

    static final class Stats {
        final long accessUnits;
        final long emitted;
        final long dropped;
        final long keyframes;
        final double fps;

        Stats(long accessUnits, long emitted, long dropped, long keyframes, double fps) {
            this.accessUnits = accessUnits;
            this.emitted = emitted;
            this.dropped = dropped;
            this.keyframes = keyframes;
            this.fps = fps;
        }
    }

    private static final class FrameState {
        final int expected;
        final TreeMap<Integer, byte[]> fragments = new TreeMap<>();
        boolean corrupt;

        FrameState(int expected) {
            this.expected = expected;
        }
    }

    private final Listener listener;
    private final Map<Integer, FrameState> frames = new HashMap<>();
    private final ArrayDeque<Integer> order = new ArrayDeque<>();
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();
    private final int maxBufferedFrames = 12;
    private int lastFinalized = -1;
    private boolean decoderStarted;
    private byte[] pendingParameters;
    private long accessUnits;
    private long emitted;
    private long dropped;
    private long keyframes;
    private long firstAccessUnitNanos;
    private long lastStatsNanos;

    LiveViewDepacketizer(Listener listener) {
        this.listener = listener;
    }

    void feed(byte[] payload, int length) {
        if (length <= 12) return;
        int frameNumber = payload[8] & 0xff;
        if (recent.contains(frameNumber)) return;

        int declaredCount = payload[9] & 0x7f;
        int position = (payload[10] & 0xff) * 2 + (((payload[9] & 0x80) != 0) ? 1 : 0);
        FrameState state = frames.get(frameNumber);
        if (state == null) {
            state = new FrameState(declaredCount);
            frames.put(frameNumber, state);
            order.addLast(frameNumber);
        } else if (state.expected != declaredCount) {
            state.corrupt = true;
        }

        byte[] fragment = Arrays.copyOfRange(payload, 12, length);
        byte[] previous = state.fragments.get(position);
        if (previous != null && !Arrays.equals(previous, fragment)) {
            state.corrupt = true;
        } else {
            state.fragments.put(position, fragment);
        }
        drain();
    }

    private void drain() {
        while (!order.isEmpty()) {
            int frameNumber = order.peekFirst();
            FrameState state = frames.get(frameNumber);
            if (complete(state)) {
                finalizeFrame(frameNumber, state);
            } else if (order.size() > maxBufferedFrames) {
                state.corrupt = true;
                finalizeFrame(frameNumber, state);
            } else {
                break;
            }
        }
    }

    private boolean complete(FrameState state) {
        if (state == null || state.expected <= 0 || state.fragments.size() != state.expected) return false;
        Integer previous = null;
        for (int position : state.fragments.keySet()) {
            if (previous != null && position != previous + 1) return false;
            previous = position;
        }
        return true;
    }

    private void finalizeFrame(int frameNumber, FrameState state) {
        order.removeFirst();
        frames.remove(frameNumber);
        accessUnits++;
        long now = System.nanoTime();
        if (firstAccessUnitNanos == 0) firstAccessUnitNanos = now;

        if (lastFinalized >= 0 && ((frameNumber - lastFinalized) & 0xff) != 1) {
            state.corrupt = true;
        }
        lastFinalized = frameNumber;

        if (state.corrupt || !complete(state)) {
            dropped++;
            if (decoderStarted) {
                decoderStarted = false;
                pendingParameters = null;
                listener.onNeedsRandomAccess();
            }
            remember(frameNumber);
            emitStats(now);
            return;
        }

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] fragment : state.fragments.values()) {
            joined.write(fragment, 0, fragment.length);
        }
        byte[] frame = stripPrivatePrefix(joined.toByteArray());
        boolean parameters = containsNalType(frame, 32) || containsNalType(frame, 33) || containsNalType(frame, 34);
        boolean randomAccess = containsRandomAccess(frame);

        if (parameters && !randomAccess) pendingParameters = frame;
        if (!decoderStarted) {
            if (!randomAccess) {
                remember(frameNumber);
                emitStats(now);
                return;
            }
            if (!parameters && pendingParameters != null) {
                byte[] combined = new byte[pendingParameters.length + frame.length];
                System.arraycopy(pendingParameters, 0, combined, 0, pendingParameters.length);
                System.arraycopy(frame, 0, combined, pendingParameters.length, frame.length);
                frame = combined;
            }
            pendingParameters = null;
            decoderStarted = true;
        }

        if (randomAccess) keyframes++;
        listener.onAccessUnit(frame);
        emitted++;
        remember(frameNumber);
        emitStats(now);
    }

    private void remember(int frameNumber) {
        recent.addLast(frameNumber);
        while (recent.size() > 128) recent.removeFirst();
    }

    private void emitStats(long now) {
        if (lastStatsNanos == 0 || now - lastStatsNanos >= 1_000_000_000L) {
            double seconds = firstAccessUnitNanos == 0 ? 0 : (now - firstAccessUnitNanos) / 1_000_000_000.0;
            double fps = seconds > 0 ? (accessUnits - 1) / seconds : 0;
            listener.onStats(new Stats(accessUnits, emitted, dropped, keyframes, fps));
            lastStatsNanos = now;
        }
    }

    private static byte[] stripPrivatePrefix(byte[] frame) {
        for (int i = 0; i + 3 < frame.length; i++) {
            if (frame[i] == 0 && frame[i + 1] == 0 && frame[i + 2] == 1 && (frame[i + 3] & 0xff) != 0xff) {
                return i == 0 ? frame : Arrays.copyOfRange(frame, i, frame.length);
            }
        }
        return frame;
    }

    private static boolean containsRandomAccess(byte[] frame) {
        for (int i = 0; i + 4 < frame.length; i++) {
            if (frame[i] == 0 && frame[i + 1] == 0 && frame[i + 2] == 1) {
                int type = (frame[i + 3] & 0x7e) >>> 1;
                if (type >= 16 && type <= 21) return true;
            }
        }
        return false;
    }

    private static boolean containsNalType(byte[] frame, int wanted) {
        for (int i = 0; i + 4 < frame.length; i++) {
            if (frame[i] == 0 && frame[i + 1] == 0 && frame[i + 2] == 1) {
                if (((frame[i + 3] & 0x7e) >>> 1) == wanted) return true;
            }
        }
        return false;
    }
}
