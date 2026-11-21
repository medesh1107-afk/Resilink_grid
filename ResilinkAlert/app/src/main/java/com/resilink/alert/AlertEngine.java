package com.resilink.alert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Holds the current alert state and makes the noise. Used by the scan service and by the test buttons. */
final class AlertEngine {
    private AlertEngine() {}

    static final String CHANNEL_ALERT = "resilink_alert";
    private static final int NOTE_ID = 2;

    static volatile int village = 4;          // node id of the village the user picked
    static volatile int kind, origin, down;   // current state of that village node
    static volatile boolean silenced;         // user pressed STOP for this state
    static volatile long lastSeenMs;          // elapsedRealtime of the last advert from my village, 0 = never

    private static volatile long simUntil;    // while a test is shown, real adverts are ignored
    private static String shownKey = "";
    private static Ringtone ringtone;
    private static PowerManager.WakeLock wake;

    /** Called for every advert from the chosen village (simulated = false) or from a test button (true). */
    static synchronized void update(Context ctx, int k, int o, int d, boolean simulated) {
        Context app = ctx.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (simulated) {
            simUntil = now + 20000;
        } else {
            lastSeenMs = now;
            if (now < simUntil) return;
        }
        String key = k + ":" + o + ":" + d;
        if (key.equals(shownKey)) return;
        shownKey = key;
        kind = k;
        origin = o;
        down = d;
        silenced = false;
        if (Msg.active(k, d)) startAlarm(app);
        else stopAll(app);
    }

    static void simulate(Context ctx, int k, int o, int d) {
        update(ctx, k, o, d, true);
    }

    /** User picked another village: forget everything. */
    static synchronized void reset(Context ctx) {
        Context app = ctx.getApplicationContext();
        shownKey = "";
        kind = 0;
        origin = 0;
        down = 0;
        silenced = false;
        lastSeenMs = 0;
        simUntil = 0;
        stopAll(app);
    }

    static synchronized void silence(Context ctx) {
        Context app = ctx.getApplicationContext();
        silenced = true;
        stopAll(app);
    }

    /** Called by the alert screen: if the sound dropped for any reason, start it again. */
    static synchronized void keepAlive(Context ctx) {
        if (!Msg.active(kind, down) || silenced) return;
        if (ringtone == null || !ringtone.isPlaying()) startSound(ctx.getApplicationContext());
    }

    private static void startAlarm(Context app) {
        startSound(app);
        wakeScreen(app);
        showNotification(app);
        Intent open = new Intent(app, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            app.startActivity(open);   // works from the background when "display over other apps" is allowed
        } catch (Exception ignored) {
        }
    }

    private static void startSound(Context app) {
        stopSound(app);
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) {
        }
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringtone = RingtoneManager.getRingtone(app, uri);
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringtone.setLooping(true);
            ringtone.play();
        } catch (Exception ignored) {
        }
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 300}, 0));
        } catch (Exception ignored) {
        }
    }

    private static void stopSound(Context app) {
        try {
            if (ringtone != null) {
                ringtone.stop();
                ringtone = null;
            }
        } catch (Exception ignored) {
        }
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            v.cancel();
        } catch (Exception ignored) {
        }
    }

    private static void stopAll(Context app) {
        stopSound(app);
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTE_ID);
        } catch (Exception ignored) {
        }
    }

    private static void wakeScreen(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (wake != null && wake.isHeld()) wake.release();
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "resilink:alert");
            wake.acquire(15000);
        } catch (Exception ignored) {
        }
    }

    private static void showNotification(Context app) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);

            Intent open = new Intent(app, AlarmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String text = Msg.body(kind, origin, down);
            Notification n = new Notification.Builder(app, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(Msg.title(kind, down))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .build();
            nm.notify(NOTE_ID, n);
        } catch (Exception ignored) {
        }
    }
}
