package com.resilink.alert;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private RadioGroup group;
    private Button btnPerm, btnOverlay, btnFull, btnBattery, btnStart;
    private TextView status, debug, liveTitle, liveBody;
    private boolean startAfterPermission = false;
    private LinearLayout livePanel;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("resilink", MODE_PRIVATE);
        AlertEngine.village = prefs.getInt("village", 4);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);

        root.addView(text("Resilink Alert", 30, true));
        root.addView(text("Pick your village. This app listens to your village node over Bluetooth and rings loudly when it sends an alert.", 16, false));

        root.addView(text("1. Your village", 20, true));
        group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < Msg.LETTERS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText("Village " + Msg.LETTERS[i]);
            rb.setTextSize(22);
            rb.setId(100 + i);
            rb.setPadding(dp(8), dp(10), dp(8), dp(10));
            group.addView(rb);
            if (Msg.IDS[i] == AlertEngine.village) rb.setChecked(true);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                int idx = checkedId - 100;
                if (idx < 0 || idx >= Msg.IDS.length) return;
                int id = Msg.IDS[idx];
                if (id == AlertEngine.village) return;
                AlertEngine.village = id;
                prefs.edit().putInt("village", id).apply();
                AlertEngine.reset(MainActivity.this);
                ScanService.heardMine = 0;
                if (prefs.getBoolean("watching", false) && hasRuntime()) {
                    startForegroundService(new Intent(MainActivity.this, ScanService.class));
                }
            }
        });
        root.addView(group);

        root.addView(text("2. Allow these once", 20, true));
        btnPerm = button("Allow Bluetooth and notifications", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRuntime();
            }
        });
        root.addView(btnPerm);
        btnOverlay = button("Allow show over other apps", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            }
        });
        root.addView(btnOverlay);
        btnFull = button("Allow full-screen alerts", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            }
        });
        root.addView(btnFull);
        btnBattery = button("Keep running in background", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            }
        });
        root.addView(btnBattery);

        root.addView(text("3. Start", 20, true));
        btnStart = button("START WATCHING", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWatch();
            }
        });
        btnStart.setTextSize(22);
        root.addView(btnStart);
        status = text("", 16, false);
        root.addView(status);
        debug = text("", 12, false);
        debug.setTypeface(Typeface.MONOSPACE);
        root.addView(debug);

        livePanel = new LinearLayout(this);
        livePanel.setOrientation(LinearLayout.VERTICAL);
        livePanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        liveTitle = text("", 26, true);
        liveBody = text("", 16, false);
        livePanel.addView(liveTitle);
        livePanel.addView(liveBody);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        root.addView(livePanel, lp);

        root.addView(text("Test without the nodes (rings at full volume)", 16, true));
        root.addView(button("Test red: EVACUATE", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertEngine.simulate(MainActivity.this, 2, 2, 0);
            }
        }));
        root.addView(button("Test red: DANGER", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertEngine.simulate(MainActivity.this, 1, 5, 0);
            }
        }));
        root.addView(button("Test yellow: HELP NEEDED", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertEngine.simulate(MainActivity.this, 3, 5, 0);
            }
        }));
        root.addView(button("Test yellow: NODE DOWN", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertEngine.simulate(MainActivity.this, 0, 0, 3);
            }
        }));

        root.addView(text("Bluetooth must be on. Stay within about 10 m of your village node.", 14, false));

        if (prefs.getBoolean("watching", false) && hasRuntime()) {
            startForegroundService(new Intent(this, ScanService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Start the scan right after the user allowed Bluetooth, no second tap needed.
        if (startAfterPermission && hasRuntime()) {
            prefs.edit().putBoolean("watching", true).apply();
            startForegroundService(new Intent(this, ScanService.class));
        }
        startAfterPermission = false;
        refresh();
    }

    // ---- actions -------------------------------------------------------------

    private void toggleWatch() {
        boolean watching = prefs.getBoolean("watching", false);
        if (watching) {
            stopService(new Intent(this, ScanService.class));
            prefs.edit().putBoolean("watching", false).apply();
            AlertEngine.reset(this);
        } else {
            if (!hasRuntime()) {
                startAfterPermission = true;
                requestRuntime();
                return;
            }
            prefs.edit().putBoolean("watching", true).apply();
            AlertEngine.reset(this);      // forget "already shown / silenced" so the same alert rings again
            startForegroundService(new Intent(this, ScanService.class));
        }
        refresh();
    }

    private boolean hasRuntime() {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN)) return false;
        if (Build.VERSION.SDK_INT <= 30 && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false;
        return true;       // notifications are asked for too, but the scan does not depend on them
    }

    private boolean hasNotif() {
        return Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** Android 10 and 11 deliver no BLE results while the phone's Location switch is off. */
    private boolean locationSwitchOk() {
        if (Build.VERSION.SDK_INT > 30) return true;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntime() {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_SCAN);
        if (Build.VERSION.SDK_INT <= 30) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(need.toArray(new String[0]), 1);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    // ---- screen refresh ------------------------------------------------------

    private void refresh() {
        boolean okPerm = hasRuntime();
        boolean okOverlay = Settings.canDrawOverlays(this);
        boolean okFull = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            okFull = nm.canUseFullScreenIntent();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean okBattery = pm.isIgnoringBatteryOptimizations(getPackageName());

        mark(btnPerm, "Allow Bluetooth and notifications", okPerm && hasNotif());
        mark(btnOverlay, "Allow show over other apps", okOverlay);
        mark(btnFull, "Allow full-screen alerts", okFull);
        mark(btnBattery, "Keep running in background", okBattery);
        btnFull.setVisibility(Build.VERSION.SDK_INT >= 34 ? View.VISIBLE : View.GONE);

        boolean watching = prefs.getBoolean("watching", false);
        btnStart.setText(watching ? "STOP WATCHING" : "START WATCHING");

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        boolean btOn = adapter != null && adapter.isEnabled();
        String letter = Msg.letterOf(AlertEngine.village);
        if (!btOn) {
            status.setText("Bluetooth is OFF. Turn it on.");
        } else if (watching && !okPerm) {
            status.setText("Bluetooth permission is missing. Tap the Allow Bluetooth button.");
        } else if (watching && !locationSwitchOk()) {
            status.setText("Turn the phone's Location switch ON (Android 10/11 needs it for Bluetooth scanning).");
        } else if (!watching) {
            status.setText("Not watching. Tap START WATCHING.");
        } else if (AlertEngine.lastSeenMs == 0) {
            status.setText("Watching Village " + letter + ". No signal from the node yet.");
        } else {
            long age = (SystemClock.elapsedRealtime() - AlertEngine.lastSeenMs) / 1000;
            if (age > 20) {
                status.setText("No signal from Village " + letter + " for " + age + " s. Move closer to the node.");
            } else {
                status.setText("Hearing the Village " + letter + " node. Last signal " + age + " s ago.");
            }
        }

        long nowMs = SystemClock.elapsedRealtime();
        String last = ScanService.lastRl1Ms == 0 ? "never" : ((nowMs - ScanService.lastRl1Ms) / 1000) + " s ago";
        debug.setText("DEBUG  service " + (ScanService.running ? "running" : "stopped")
                + "\nscan: " + (ScanService.mode.isEmpty() ? "-" : ScanService.mode)
                + "\nRL1 adverts heard: " + ScanService.heardRl1 + " (my village: " + ScanService.heardMine
                + "), other BLE: " + ScanService.heardOther
                + "\nlast RL1: " + last + "  " + ScanService.lastDecoded
                + "\nraw: " + (ScanService.lastRaw.isEmpty() ? "-" : ScanService.lastRaw)
                + (ScanService.lastError.isEmpty() ? "" : "\nerror: " + ScanService.lastError)
                + (hasNotif() ? "" : "\nnotifications not allowed (alerts still ring)"));

        int k = AlertEngine.kind, o = AlertEngine.origin, d = AlertEngine.down;
        int c = Msg.colour(k, d);
        int bg = c == Msg.RED ? Color.parseColor("#C62828")
                : c == Msg.YELLOW ? Color.parseColor("#F9A825") : Color.parseColor("#2E7D32");
        int fg = c == Msg.YELLOW ? Color.BLACK : Color.WHITE;
        livePanel.setBackgroundColor(bg);
        liveTitle.setTextColor(fg);
        liveBody.setTextColor(fg);
        liveTitle.setText(Msg.title(k, d));
        liveBody.setText(Msg.body(k, o, d));
        livePanel.setOnClickListener(Msg.active(k, d) ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AlarmActivity.class));
            }
        } : null);
    }

    private void mark(Button b, String label, boolean ok) {
        b.setText(ok ? "DONE: " + label : label);
        b.setEnabled(!ok);
    }

    // ---- small UI helpers ----------------------------------------------------

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
