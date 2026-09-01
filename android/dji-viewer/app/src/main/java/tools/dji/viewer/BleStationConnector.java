package tools.dji.viewer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class BleStationConnector implements AutoCloseable {
    interface Listener {
        void onStatus(String status);
    }

    private static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF4 = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF5 = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final String ssid;
    private final String password;
    private final Listener listener;
    private final CountDownLatch completed = new CountDownLatch(1);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic fff4;
    private BluetoothGattCharacteristic fff5;
    private byte[] receiveBuffer = new byte[0];
    private volatile boolean joined;
    private volatile boolean closed;
    private volatile String error;
    private boolean stationStarted;
    private int notificationStep;

    BleStationConnector(Context context, String ssid, String password, Listener listener) {
        this.context = context.getApplicationContext();
        this.ssid = ssid;
        this.password = password;
        this.listener = listener;
    }

    boolean connectAndJoin(long timeoutSeconds) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("Bluetooth is disabled");
            return false;
        }
        scanner = adapter.getBluetoothLeScanner();
        listener.onStatus("BLE: searching for Osmo Action 4…");
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        try {
            if (!completed.await(timeoutSeconds, TimeUnit.SECONDS)) fail("Timed out joining camera to Wi-Fi");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("BLE operation interrupted");
        }
        return joined;
    }

    String getError() {
        return error == null ? "Camera Wi-Fi join failed" : error;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = result.getDevice().getName();
            if (name == null || !name.toUpperCase().contains("OA4")) return;
            scanner.stopScan(this);
            listener.onStatus("BLE: connecting to " + name + "…");
            gatt = result.getDevice().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        }

        @Override public void onScanFailed(int errorCode) {
            fail("BLE scan failed (" + errorCode + ")");
        }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!closed && !joined) fail("BLE disconnected (status " + status + ")");
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("BLE: connected; negotiating transport…");
                if (!connection.requestMtu(185)) connection.discoverServices();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            connection.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            BluetoothGattService service = connection.getService(SERVICE);
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                fail("OA4 BLE service was not found");
                return;
            }
            fff4 = service.getCharacteristic(FFF4);
            fff5 = service.getCharacteristic(FFF5);
            if (fff4 == null || fff5 == null) {
                fail("OA4 BLE characteristics were not found");
                return;
            }
            notificationStep = 1;
            enableNotifications(connection, fff4);
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not enable OA4 BLE notifications");
                return;
            }
            if (notificationStep == 1) {
                notificationStep = 2;
                enableNotifications(connection, fff5);
            } else {
                notificationStep = 3;
                listener.onStatus("BLE: opening DJI session…");
                write(fff4, new byte[] {1, 0}, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.getUuid().equals(FFF4)) {
                handler.postDelayed(() -> {
                    send(build(0xf002, 0x102b, 0x40, 0x00, 0x2b, new byte[] {4, 0}));
                    handler.postDelayed(BleStationConnector.this::sendPair, 100);
                }, 250);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) receive(value);
        }
    };

    private void enableNotifications(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
        connection.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            fail("OA4 notification descriptor is missing");
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        connection.writeDescriptor(descriptor);
    }

    private void sendPair() {
        byte[] identifier = pack("646a692d746f6f6c732d6f61342d3031");
        byte[] label = pack("dji-tools");
        byte[] payload = concat(identifier, label);
        send(build(0x0702, 0x1092, 0x40, 0x07, 0x45, payload));
    }

    private synchronized void receive(byte[] chunk) {
        receiveBuffer = concat(receiveBuffer, chunk);
        while (receiveBuffer.length >= 13) {
            int start = 0;
            while (start < receiveBuffer.length && receiveBuffer[start] != 0x55) start++;
            if (start > 0) receiveBuffer = Arrays.copyOfRange(receiveBuffer, start, receiveBuffer.length);
            if (receiveBuffer.length < 4) return;
            int length = (receiveBuffer[1] & 0xff) | ((receiveBuffer[2] & 3) << 8);
            if (length < 13 || length > 1023) {
                receiveBuffer = Arrays.copyOfRange(receiveBuffer, 1, receiveBuffer.length);
                continue;
            }
            if (receiveBuffer.length < length) return;
            byte[] frame = Arrays.copyOfRange(receiveBuffer, 0, length);
            receiveBuffer = Arrays.copyOfRange(receiveBuffer, length, receiveBuffer.length);
            if (Duml.crc8(frame, 0, 3) != (frame[3] & 0xff)) continue;
            if (Duml.crc16(frame, 0, length - 2) != Duml.u16(frame, length - 2)) continue;
            handle(frame);
        }
    }

    private void handle(byte[] frame) {
        int target = Duml.u16(frame, 4);
        int messageId = ((frame[6] & 0xff) << 8) | (frame[7] & 0xff);
        int flags = frame[8] & 0xff;
        int commandSet = frame[9] & 0xff;
        int commandId = frame[10] & 0xff;
        byte[] payload = Arrays.copyOfRange(frame, 11, frame.length - 2);

        if (flags == 0x40) {
            if (commandSet == 0x07 && commandId == 0x46) {
                int swappedTarget = ((target & 0xff) << 8) | ((target >>> 8) & 0xff);
                send(build(swappedTarget, messageId, 0xc0, commandSet, commandId, payload));
                listener.onStatus("BLE: camera approved dji-viewer");
                handler.postDelayed(this::beginStationJoin, 250);
            }
            return;
        }

        if (commandSet == 0x07 && commandId == 0x45) {
            int pairStatus = payload.length >= 2 ? payload[1] & 0xff : 0xff;
            if (pairStatus == 1) beginStationJoin();
            else if (pairStatus == 2) listener.onStatus("Approve dji-tools on the camera screen…");
            else fail("OA4 pairing failed (status " + pairStatus + ")");
            return;
        }
        if (messageId == 0x10c8) {
            listener.onStatus("BLE: preparing camera Wi-Fi…");
            send(build(0x0802, 0x1012, 0x40, 0x02, 0xe1, new byte[] {0x1a}));
        } else if (messageId == 0x1012) {
            listener.onStatus("BLE: joining “" + ssid + "”…");
            send(build(0x0702, 0x1019, 0x40, 0x07, 0x47, concat(pack(ssid), pack(password))));
        } else if (messageId == 0x1019) {
            if (payload.length >= 2 && payload[0] == 0 && payload[1] == 0) {
                joined = true;
                listener.onStatus("BLE: camera joined Wi-Fi; discovering its address…");
                completed.countDown();
            } else {
                fail("Camera rejected the Wi-Fi credentials");
            }
        }
    }

    private void beginStationJoin() {
        if (stationStarted) return;
        stationStarted = true;
        listener.onStatus("BLE: switching camera to station mode…");
        send(build(0x0802, 0x10c8, 0x40, 0x02, 0x8e, new byte[] {1, 1, 0x1a, 0, 1, 2}));
    }

    private void send(byte[] data) {
        if (closed || gatt == null || fff5 == null) return;
        write(fff5, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
    }

    private void write(BluetoothGattCharacteristic characteristic, byte[] data, int type) {
        characteristic.setWriteType(type);
        characteristic.setValue(data);
        gatt.writeCharacteristic(characteristic);
    }

    private static byte[] build(int target, int messageId, int flags, int commandSet, int commandId, byte[] payload) {
        int length = 13 + payload.length;
        byte[] frame = new byte[length];
        frame[0] = 0x55;
        frame[1] = (byte) length;
        frame[2] = (byte) (((length >>> 8) & 3) | 4);
        frame[3] = (byte) Duml.crc8(frame, 0, 3);
        Duml.putU16(frame, 4, target);
        frame[6] = (byte) (messageId >>> 8);
        frame[7] = (byte) messageId;
        frame[8] = (byte) flags;
        frame[9] = (byte) commandSet;
        frame[10] = (byte) commandId;
        System.arraycopy(payload, 0, frame, 11, payload.length);
        Duml.putU16(frame, length - 2, Duml.crc16(frame, 0, length - 2));
        return frame;
    }

    private static byte[] pack(String value) {
        byte[] text = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (text.length > 255) throw new IllegalArgumentException("Packed string is too long");
        byte[] result = new byte[text.length + 1];
        result[0] = (byte) text.length;
        System.arraycopy(text, 0, result, 1, text.length);
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private void fail(String message) {
        if (error == null) error = message;
        completed.countDown();
    }

    @Override public void close() {
        closed = true;
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            gatt.close();
            gatt = null;
        }
        completed.countDown();
    }
}
