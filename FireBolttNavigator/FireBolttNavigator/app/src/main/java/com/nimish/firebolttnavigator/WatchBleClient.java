package com.nimish.firebolttnavigator;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Android BLE client for the experimentally documented MoYoung V2 Type-C face upload path. */
public final class WatchBleClient {
    public interface Listener {
        void onStatus(String message);
        void onConnected(String deviceName);
        void onDisconnected(String reason);
        void onUploadProgress(int sentBytes, int totalBytes, int chunkIndex, int totalChunks);
        void onUploadComplete(String ackHex);
        void onError(String message);
    }

    public static final String DEFAULT_ADDRESS = "F1:70:C6:5B:E3:F6";
    private static final UUID SERVICE_UUID = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb");
    private static final UUID CONTROL_UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb");
    private static final UUID DATA_UUID = UUID.fromString("0000fee6-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] ENABLE_NOTIFICATION = new byte[]{0x01, 0x00};
    private static final int CHUNK_SIZE = 244;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control;
    private BluetoothGattCharacteristic notify;
    private BluetoothGattCharacteristic data;
    private boolean connected;
    private boolean ready;
    private boolean uploading;
    private int negotiatedMtu = 23;
    private byte[] uploadBytes;
    private int chunksSent;
    private long ackValue = -1;
    private Runnable scanTimeout;
    private Runnable uploadTimeout;

    public WatchBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isConnected() { return connected && ready && gatt != null; }

    public void connect(String targetAddress) {
        if (!hasBluetoothPermissions()) {
            listener.onError("Grant Nearby devices / Bluetooth permissions first.");
            return;
        }
        if (isConnected()) {
            listener.onStatus("Fire-Boltt is already connected to this app.");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("Turn Bluetooth on, then try again.");
            return;
        }
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }

        connected = false;
        ready = false;
        negotiatedMtu = 23;
        String wanted = targetAddress == null ? DEFAULT_ADDRESS : targetAddress.trim();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            try {
                List<BluetoothDevice> already = manager.getConnectedDevices(BluetoothProfile.GATT);
                for (BluetoothDevice device : already) {
                    if (wanted.equalsIgnoreCase(device.getAddress())) {
                        listener.onError("Android reports this watch already connected to a GATT client. Close Da Fit, wait a few seconds, then retry. Android cannot take over another app's GATT connection.");
                        return;
                    }
                }
            } catch (SecurityException ignored) {}
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(wanted);
            listener.onStatus("Connecting directly to " + wanted + " …");
            beginGatt(device);
        } catch (IllegalArgumentException e) {
            scanForWatch(wanted);
        } catch (SecurityException e) {
            listener.onError("Bluetooth permission was not granted: " + e.getMessage());
        }
    }

    private void scanForWatch(String wantedAddress) {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { listener.onError("BLE scanner is unavailable."); return; }
            listener.onStatus("Scanning for FireBoltt 046 …");
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            scanTimeout = () -> {
                stopScan();
                listener.onError("Watch not found. Keep the watch awake and close Da Fit before connecting.");
            };
            main.postDelayed(scanTimeout, 15000);
        } catch (SecurityException e) {
            listener.onError("Bluetooth scan permission denied: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            try {
                String address = device.getAddress();
                String name = device.getName();
                boolean exact = DEFAULT_ADDRESS.equalsIgnoreCase(address);
                boolean nameMatch = name != null && (name.toLowerCase().contains("fireboltt")
                        || name.toLowerCase().contains("fire-boltt") || name.toLowerCase().contains("046"));
                if (exact || nameMatch) {
                    stopScan();
                    listener.onStatus("Found " + (name == null ? address : name) + ". Connecting …");
                    beginGatt(device);
                }
            } catch (SecurityException e) {
                stopScan();
                listener.onError("Bluetooth permission denied while reading scan result.");
            }
        }

        @Override public void onScanFailed(int errorCode) {
            stopScan();
            listener.onError("BLE scan failed: " + errorCode);
        }
    };

    private void beginGatt(BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
            if (gatt == null) listener.onError("Android could not create a GATT connection.");
        } catch (SecurityException e) {
            listener.onError("Bluetooth connect permission denied: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connected = false; ready = false;
                listener.onError("GATT connection failed (status " + status + "). Close Da Fit and retry.");
                closeGatt();
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                listener.onStatus("Connected. Negotiating BLE MTU and discovering MoYoung services …");
                boolean requested = false;
                try { requested = current.requestMtu(247); } catch (SecurityException ignored) {}
                if (!requested) current.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasUploading = uploading;
                connected = false; ready = false; uploading = false;
                listener.onDisconnected(wasUploading ? "Watch disconnected during upload." : "Watch disconnected.");
                closeGatt();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt current, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
            current.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("GATT service discovery failed: " + status); return;
            }
            BluetoothGattService service = current.getService(SERVICE_UUID);
            if (service == null) { listener.onError("MoYoung service FEEA was not found on this device."); return; }
            control = service.getCharacteristic(CONTROL_UUID);
            notify = service.getCharacteristic(NOTIFY_UUID);
            data = service.getCharacteristic(DATA_UUID);
            if (control == null || notify == null || data == null) {
                listener.onError("Watch is missing one of FEE2, FEE3 or FEE6. This does not look like the captured MoYoung V2 layout."); return;
            }
            try {
                current.setCharacteristicNotification(notify, true);
                BluetoothGattDescriptor descriptor = notify.getDescriptor(CCCD_UUID);
                if (descriptor == null) { listener.onError("FEE3 notification descriptor not found."); return; }
                if (Build.VERSION.SDK_INT >= 33) {
                    current.writeDescriptor(descriptor, ENABLE_NOTIFICATION);
                } else {
                    descriptor.setValue(ENABLE_NOTIFICATION);
                    current.writeDescriptor(descriptor);
                }
            } catch (SecurityException e) {
                listener.onError("Bluetooth permission denied while enabling notifications.");
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true;
                String name = "Fire-Boltt 046";
                try { if (current.getDevice().getName() != null) name = current.getDevice().getName(); }
                catch (SecurityException ignored) {}
                listener.onConnected(name);
                listener.onStatus("MoYoung V2 link ready (FEE2 / FEE3 / FEE6). Type-C upload is experimental on this exact firmware.");
            } else listener.onError("Could not enable watch notifications: " + status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
            if (NOTIFY_UUID.equals(characteristic.getUuid())) handleNotification(characteristic.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (NOTIFY_UUID.equals(characteristic.getUuid())) handleNotification(value);
        }
    };

    private void handleNotification(byte[] frame) {
        if (!uploading || frame == null || frame.length < 5) return;
        if ((frame[0] & 0xff) != 0xfe || (frame[1] & 0xff) != 0xea || (frame[4] & 0xff) != 0x74) return;
        int payloadLength = frame[3] & 0xff;
        if (payloadLength == 7 && frame.length >= 7) {
            int index = ((frame[5] & 0xff) << 8) | (frame[6] & 0xff);
            int start = index * CHUNK_SIZE;
            if (start >= uploadBytes.length) {
                failUpload("Watch requested chunk " + index + " past the end of the file.");
                return;
            }
            int end = Math.min(start + CHUNK_SIZE, uploadBytes.length);
            byte[] piece = new byte[end - start];
            System.arraycopy(uploadBytes, start, piece, 0, piece.length);
            if (writeNoResponse(data, piece)) {
                chunksSent++;
                listener.onUploadProgress(end, uploadBytes.length, index + 1,
                        (uploadBytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
            } else failUpload("Could not write chunk " + index + " to FEE6.");
            return;
        }
        if (payloadLength == 9 && frame.length >= 9) {
            long ack = ((long)(frame[5] & 0xff) << 24)
                    | ((long)(frame[6] & 0xff) << 16)
                    | ((long)(frame[7] & 0xff) << 8)
                    | (long)(frame[8] & 0xff);
            ackValue = ack;
            main.removeCallbacks(uploadTimeout);
            // Gallery/face upload completion handshake documented for other MoYoung V2 watches.
            main.post(() -> {
                writeNoResponse(control, hex("FE EA 20 09 74 00 00 00 00"));
                main.postDelayed(() -> {
                    writeNoResponse(control, hex("FE EA 20 06 19 0D"));
                    uploading = false;
                    String ackHex = String.format("0x%08X", ackValue);
                    listener.onUploadComplete(ackHex);
                }, 260);
            });
        }
    }

    public void uploadTypeCFace(byte[] faceFile) {
        if (!isConnected()) { listener.onError("Connect the watch before uploading the route."); return; }
        if (uploading) { listener.onError("A watch upload is already running."); return; }
        if (negotiatedMtu < 247) {
            listener.onError("BLE MTU is " + negotiatedMtu + ". This upload protocol requires MTU 247 for 244-byte chunks. Reconnect and retry; do not change the chunk size.");
            return;
        }
        if (faceFile == null || faceFile.length < 1900 || (faceFile[0] & 0xff) != 0x81) {
            listener.onError("The generated file does not look like a Type-C MoYoung face."); return;
        }
        uploadBytes = faceFile;
        chunksSent = 0;
        ackValue = -1;
        uploading = true;
        uploadTimeout = () -> failUpload("Timed out waiting for the watch's 0x74 chunk requests/completion. The 0x74 route has not yet been validated on this exact model.");
        main.postDelayed(uploadTimeout, 180000);
        byte[] start = new byte[9];
        byte[] prefix = hex("FE EA 20 09 74");
        System.arraycopy(prefix, 0, start, 0, prefix.length);
        int length = faceFile.length;
        start[5] = (byte) (length >>> 24);
        start[6] = (byte) (length >>> 16);
        start[7] = (byte) (length >>> 8);
        start[8] = (byte) length;
        listener.onStatus("Starting experimental 0x74 Type-C face upload (" + length + " bytes). Keep the watch close to the phone.");
        if (!writeNoResponse(control, start)) failUpload("Could not send the 0x74 start command.");
    }

    /** Optional MoYoung message command for a turn-by-turn text alert. */
    public void sendTurnAlert(String message) {
        if (!isConnected() || uploading) return;
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[text.length + 1];
        payload[0] = 0x0b; // OTHER notification type in the public MoYoung command mapping.
        System.arraycopy(text, 0, payload, 1, text.length);
        int total = payload.length + 5;
        byte[] packet = new byte[total];
        packet[0] = (byte)0xfe; packet[1] = (byte)0xea;
        packet[2] = (byte)(0x20 + (total >>> 8)); packet[3] = (byte)total;
        packet[4] = 0x41;
        System.arraycopy(payload, 0, packet, 5, payload.length);
        writeNoResponse(control, packet);
    }

    private boolean writeNoResponse(BluetoothGattCharacteristic characteristic, byte[] bytes) {
        if (gatt == null || characteristic == null || !connected) return false;
        try {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            characteristic.setValue(bytes);
            return gatt.writeCharacteristic(characteristic);
        } catch (SecurityException e) {
            listener.onError("Bluetooth write permission denied.");
            return false;
        }
    }

    private void failUpload(String message) {
        if (!uploading) return;
        uploading = false;
        main.removeCallbacks(uploadTimeout);
        listener.onError(message);
    }

    public void disconnect() {
        stopScan();
        if (uploadTimeout != null) main.removeCallbacks(uploadTimeout);
        uploading = false;
        closeGatt();
    }

    private void closeGatt() {
        ready = false; connected = false;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        control = null; notify = null; data = null;
    }

    private void stopScan() {
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        if (scanner != null && hasBluetoothPermissions()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanner = null;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
    }

    private static byte[] hex(String value) {
        String clean = value.replace(" ", "");
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
