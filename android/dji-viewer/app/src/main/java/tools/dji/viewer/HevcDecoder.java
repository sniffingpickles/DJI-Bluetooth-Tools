package tools.dji.viewer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

final class HevcDecoder implements AutoCloseable {
    interface ErrorListener {
        void onDecoderError(String message);
    }

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(90);
    private final MediaCodec codec;
    private final Thread worker;
    private final ErrorListener errors;
    private volatile boolean running = true;
    private long presentationUs;

    HevcDecoder(Surface surface, ErrorListener errors) throws IOException {
        this.errors = errors;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720);
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
        codec.configure(format, surface, null, 0);
        codec.start();
        worker = new Thread(this::decodeLoop, "oa4-hevc-decoder");
        worker.start();
    }

    void queue(byte[] accessUnit) {
        if (!running) return;
        if (!queue.offer(accessUnit)) {
            queue.poll();
            queue.offer(accessUnit);
        }
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                byte[] accessUnit = queue.poll(50, TimeUnit.MILLISECONDS);
                if (accessUnit != null) {
                    int inputIndex = codec.dequeueInputBuffer(20_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input != null && input.capacity() >= accessUnit.length) {
                            input.clear();
                            input.put(accessUnit);
                            codec.queueInputBuffer(inputIndex, 0, accessUnit.length, presentationUs, 0);
                            presentationUs += 33_333;
                        } else {
                            errors.onDecoderError("HEVC access unit exceeds decoder input buffer");
                        }
                    } else {
                        queue.offer(accessUnit);
                    }
                }

                while (running) {
                    int outputIndex = codec.dequeueOutputBuffer(info, 0);
                    if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, true);
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Expected once the OA4 VPS/SPS/PPS have been parsed.
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception error) {
            if (running) errors.onDecoderError(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try { worker.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { codec.stop(); } catch (Exception ignored) {}
        codec.release();
        queue.clear();
    }
}
