package com.resilink.alert;

import android.Manifest;
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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
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

    // ---- debug info shown on the main screen ----
    static volatile int heardMine;           // RL1 adverts heard from the picked village
    static volatile int heardRl1;            // RL1 adverts heard from any village
    static volatile int heardOther;          // other BLE adverts seen (only grows while scanning unfiltered)
    static volatile long lastRl1Ms;          // elapsedRealtime of the last RL1 advert, 0 = never
    static volatile String lastRaw = "";     // hex of the whole scan record of the last RL1 advert
    static volatile String lastDecoded = ""; // v k o d decoded from it
    static volatile String lastError = "";   // last scan error / start problem
    static volatile String mode = "";        // what kind of scan is running
    static volatile boolean running;         // service alive

    // Village A has no advert of its own: it listens to B, C and D and keeps its last alert for a short time.
    private int aK, aO, aD;
    private long aHoldUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private boolean useFilter = true;        // loose hardware filter first, unfiltered as the fallback
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
            lastError = "scan failed, code " + errorCode;
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            ensureScanning();
            handler.postDelayed(this, 5000);
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

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        // Android 10 and 11 only deliver scan results to a background app if the service is a location service.
        if (Build.VERSION.SDK_INT <= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        try {
            startForeground(1, n, type);
        } catch (Exception e) {
            lastError = "startForeground refused: " + e.getClass().getSimpleName() + " " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        // Village may have changed: begin with a clean scan so the name filter is rebuilt.
        stopScanQuietly();
        useFilter = true;

        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        return START_STICKY;
    }

    private boolean canScan() {
        String p = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void stopScanQuietly() {
        try {
            if (scanner != null && scanning) scanner.stopScan(callback);
        } catch (Exception ignored) {
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    private void ensureScanning() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            scanning = false;           // Bluetooth is off; the old scanner is dead, start fresh when it is back
            lastError = "Bluetooth is off";
            mode = "";
            return;
        }
        if (!canScan()) {
            stopScanQuietly();
            lastError = "scan permission not granted";
            mode = "";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (scanning) {
            // Nothing heard for 20 s: restart, and flip between "loose filter" and "no filter" in case this
            // phone's Bluetooth chip mishandles one of them. Unfiltered scans stop delivering when the screen
            // is off (Android 8.1+), so the loose filter is the one that must work on a locked phone.
            long quiet = now - Math.max(lastAnyAdvert, lastScanStart);
            if (quiet > 20000) {
                stopScanQuietly();
                useFilter = !useFilter;
            } else {
                return;
            }
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                lastError = "no BLE scanner yet (Bluetooth starting?)";
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            if (useFilter) {
                // Loose on purpose: any manufacturer data with company id 0xFFFF (mask 0 = any bytes), or the
                // advert name RESILINK-<letter>. The real check is done in handle().
                List<ScanFilter> filters = new ArrayList<ScanFilter>();
                filters.add(new ScanFilter.Builder()
                        .setManufacturerData(COMPANY_ID, new byte[]{0}, new byte[]{0})
                        .build());
                filters.add(new ScanFilter.Builder()
                        .setDeviceName("RESILINK-" + Msg.letterOf(AlertEngine.village))
                        .build());
                scanner.startScan(filters, settings, callback);
                mode = "loose filter (id 0xFFFF or name)";
            } else {
                scanner.startScan(null, settings, callback);
                mode = "NO filter (checked in code)";
            }
            scanning = true;
            lastScanStart = now;
            lastError = "";
        } catch (Exception e) {
            scanning = false;
            lastError = "startScan: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Index of the 'R' of the ASCII bytes R L 1 with at least 4 bytes after them, or -1. */
    private static int findRl1(byte[] raw) {
        if (raw == null) return -1;
        for (int i = 0; i + 6 < raw.length; i++) {
            if (raw[i] == 'R' && raw[i + 1] == 'L' && raw[i + 2] == '1') return i;
        }
        return -1;
    }

    private void handle(ScanResult result) {
        ScanRecord rec = result.getScanRecord();
        if (rec == null) return;
        byte[] d = rec.getManufacturerSpecificData(COMPANY_ID);
        int off = 0;
        if (d == null || d.length < 7 || d[0] != 'R' || d[1] != 'L' || d[2] != '1') {
            // Fallback: look for "RL1" anywhere in the raw advert, in case the parser did not split it as expected.
            byte[] raw = rec.getBytes();
            off = findRl1(raw);
            if (off < 0) {
                heardOther++;
                return;
            }
            d = raw;
        }
        lastAnyAdvert = SystemClock.elapsedRealtime();
        int v = d[off + 3] - '0';
        int k = d[off + 4] - '0';
        int o = d[off + 5] - '0';
        int dn = d[off + 6] - '0';
        heardRl1++;
        lastRl1Ms = lastAnyAdvert;
        lastRaw = hex(rec.getBytes());
        lastDecoded = "v=" + v + " k=" + k + " o=" + o + " d=" + dn;
        if (k < 0 || k > 4 || o < 0 || o > 9 || dn < 0 || dn > 9) return;   // not a sane packet
        int mine = AlertEngine.village;
        // Only problems of MY village are shown: an alert counts only if my village raised it,
        // and a node-down notice only if it is my village that is down.
        int ek = (k != 0 && o == mine) ? k : 0;
        int eo = ek != 0 ? o : 0;
        int ed = (dn == mine) ? dn : 0;
        Context app = getApplicationContext();
        if (Msg.hasBle(mine)) {
            if (v != mine) return;               // only the advert of the village the user picked
            heardMine++;
            AlertEngine.update(app, ek, eo, ed, false);
        } else {
            // Village A: any node's advert may carry A's alert. Adverts that say nothing do not clear it
            // until 10 s have passed, because another node may simply not have heard about it yet.
            heardMine++;
            long now = SystemClock.elapsedRealtime();
            if (ek != 0 || ed != 0) {
                aK = ek;
                aO = eo;
                aD = ed;
                aHoldUntil = now + 10000;
            } else if (now >= aHoldUntil) {
                aK = 0;
                aO = 0;
                aD = 0;
            }
            AlertEngine.update(app, aK, aO, aD, false);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        stopScanQuietly();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
