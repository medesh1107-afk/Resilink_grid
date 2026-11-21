package com.resilink.alert;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;

/**
 * Listens for the Resilink village nodes. A node advertises one small packet:
 * company id 0xFFFF, then the ASCII bytes  R L 1 v k o d
 * v = village id, k = alert kind, o = node that raised it, d = node that is down,
 * each digit is '0' + number.
 */
public class ScanService extends Service {
    static final String CHANNEL = "resilink_watch";
    private static final int COMPANY_ID = 0xFFFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private long lastScanStart = 0;
    private long lastAnyAdvert = 0;

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            ensureScanning();
            handler.postDelayed(this, 8000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("resilink", MODE_PRIVATE);
        AlertEngine.village = prefs.getInt("village", 4);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Watching", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Resilink Alert is watching")
                .setContentText("Village " + Msg.letterOf(AlertEngine.village) + ". Keep Bluetooth on.")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void ensureScanning() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            scanning = false;           // Bluetooth is off, try again later
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (scanning) {
            // Nothing heard for a long time: restart the scan once in a while, in case Bluetooth was toggled.
            if (now - lastAnyAdvert > 45000 && now - lastScanStart > 45000) {
                try {
                    if (scanner != null) scanner.stopScan(callback);
                } catch (Exception ignored) {
                }
                scanning = false;
            } else {
                return;
            }
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(COMPANY_ID,
                            new byte[]{'R', 'L', '1'},
                            new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF})
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanning = true;
            lastScanStart = now;
        } catch (Exception e) {
            scanning = false;
        }
    }

    private void handle(ScanResult result) {
        ScanRecord rec = result.getScanRecord();
        if (rec == null) return;
        byte[] d = rec.getManufacturerSpecificData(COMPANY_ID);
        if (d == null || d.length < 7) return;
        if (d[0] != 'R' || d[1] != 'L' || d[2] != '1') return;
        lastAnyAdvert = SystemClock.elapsedRealtime();
        int v = d[3] - '0';
        int k = d[4] - '0';
        int o = d[5] - '0';
        int dn = d[6] - '0';
        if (v != AlertEngine.village) return;    // only the village the user picked
        AlertEngine.update(getApplicationContext(), k, o, dn, false);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        try {
            if (scanner != null && scanning) scanner.stopScan(callback);
        } catch (Exception ignored) {
        }
        scanning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
