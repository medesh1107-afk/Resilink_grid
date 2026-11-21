package com.resilink.alert;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen alert. Shows over the lock screen and over other apps. */
public class AlarmActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView head, title, body;
    private Button stop, close;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(32));

        head = new TextView(this);
        head.setTextSize(20);
        head.setGravity(Gravity.CENTER);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(this);
        title.setTextSize(44);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, dp(16));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        body = new TextView(this);
        body.setTextSize(22);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(body, bl);

        stop = new Button(this);
        stop.setText("STOP ALARM");
        stop.setTextSize(26);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertEngine.silence(AlarmActivity.this);
                refresh();
            }
        });
        root.addView(stop, new LinearLayout.LayoutParams(-1, dp(90)));

        close = new Button(this);
        close.setText("CLOSE");
        close.setTextSize(20);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(close, new LinearLayout.LayoutParams(-1, dp(70)));

        setContentView(root);
        refresh();
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

    private void refresh() {
        int k = AlertEngine.kind, o = AlertEngine.origin, d = AlertEngine.down;
        boolean active = Msg.active(k, d);
        int colour = Msg.colour(k, d);
        int bg, fg;
        if (colour == Msg.RED) {
            bg = Color.parseColor("#C62828");
            fg = Color.WHITE;
        } else if (colour == Msg.YELLOW) {
            bg = Color.parseColor("#F9A825");
            fg = Color.BLACK;
        } else {
            bg = Color.parseColor("#2E7D32");
            fg = Color.WHITE;
        }
        root.setBackgroundColor(bg);
        head.setTextColor(fg);
        title.setTextColor(fg);
        body.setTextColor(fg);
        head.setText("VILLAGE " + Msg.letterOf(AlertEngine.village));
        title.setText(Msg.title(k, d));
        body.setText(Msg.body(k, o, d));

        boolean ringing = active && !AlertEngine.silenced;
        stop.setVisibility(ringing ? View.VISIBLE : View.GONE);
        close.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (ringing) AlertEngine.keepAlive(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
