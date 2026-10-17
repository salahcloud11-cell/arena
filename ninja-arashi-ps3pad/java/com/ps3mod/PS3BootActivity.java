package com.ps3mod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Boot screen for the Ninja Arashi PS3 mod.
 *
 * This activity is the launcher: the game's own activity cannot be trusted to
 * prove anything, because if the mod failed the player would only see "nothing
 * happens".  This screen is drawn before Unity starts, so it shows, without a
 * controller and without guessing:
 *
 *   - that the mod is installed and running (the version line, from this class);
 *   - every input device Android reports (name, id, sources, vendor/product);
 *   - live controller input: key codes and stick axes as they arrive;
 *   - a start button that launches the real game activity.
 *
 * Nothing here is required for gameplay: it is a diagnostic front door.
 */
public class PS3BootActivity extends Activity {

    private TextView devicesView;
    private TextView liveView;
    private TextView touchView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PS3 MOD v5 — المود محمّل ✓");
        title.setTextColor(Color.rgb(120, 255, 120));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("هاد الشاشة كتطلع قبل اللعبة. إلا شفتيها، النسخة الجديدة تثبتات وخدامة.\n"
                + "وصّل اليد دابا (USB OTG أولا) واضغط زر — خاصك تشوف الكود تحت.");
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        col.addView(sub);

        Button start = new Button(this);
        start.setText("▶ بدء Ninja Arashi (مع اليد)");
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startGame();
            }
        });
        col.addView(start);

        Button startPlain = new Button(this);
        startPlain.setText("بدء اللعبة بلا مود (تشخيص)");
        startPlain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        startPlain.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent();
                    i.setClassName(getPackageName(), "com.unity3d.player.UnityPlayerActivity");
                    i.putExtra("ps3mod.disabled", true);
                    startActivity(i);
                } catch (Throwable t) {
                    live("ما قدرتش نفتح اللعبة: " + t);
                }
            }
        });
        col.addView(startPlain);

        devicesView = new TextView(this);
        devicesView.setTextColor(Color.rgb(180, 220, 255));
        devicesView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        col.addView(devicesView);

        liveView = new TextView(this);
        liveView.setTextColor(Color.YELLOW);
        liveView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        liveView.setText("آخر إدخال من اليد: —");
        col.addView(liveView);

        touchView = new TextView(this);
        touchView.setTextColor(Color.LTGRAY);
        touchView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        touchView.setText("لمسات الشاشة: —");
        col.addView(touchView);

        TextView note = new TextView(this);
        note.setText("\nملاحظة: إلا كان الزر كيوصل هنا ولكن اللعبة ما كتستجيبش، المشكل فحقن اللمسات.\n"
                + "إلا ما كيوصلش حتى زر هنا، المشكل فالفورمطة (أندرويد ما شافش اليد).");
        note.setTextColor(Color.GRAY);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        col.addView(note);

        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refreshDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
        handler.postDelayed(new Runnable() {
            public void run() {
                refreshDevices();
            }
        }, 1500L);
    }

    private void startGame() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), "com.unity3d.player.UnityPlayerActivity");
            startActivity(i);
            finish();
        } catch (Throwable t) {
            live("ما قدرتش نفتح اللعبة: " + t);
        }
    }

    private void refreshDevices() {
        StringBuilder sb = new StringBuilder();
        int pads = 0;
        try {
            int[] ids = InputDevice.getDeviceIds();
            sb.append("أجهزة الإدخال اللي كيشوفها أندرويد (").append(ids.length).append("):\n");
            for (int id : ids) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) {
                    continue;
                }
                int src = d.getSources();
                boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                if (pad) {
                    pads++;
                }
                sb.append(pad ? "  ★ " : "  • ")
                        .append("id=").append(id).append("  ").append(d.getName())
                        .append("  sources=0x").append(Integer.toHexString(src)).append("\n");
            }
        } catch (Throwable t) {
            sb.append("خطأ: ").append(t).append("\n");
        }
        sb.append("\nعدد اليدات (gamepad/joystick): ").append(pads)
                .append(pads == 0 ? "  ← أندرويد ما شافش حتى يد!" : "  ✓");
        devicesView.setText(sb.toString());
    }

    private void live(String msg) {
        if (liveView != null) {
            liveView.setText(msg);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        try {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                String code = KeyEvent.keyCodeToString(event.getKeyCode());
                InputDevice d = event.getDevice();
                live("آخر زر: " + event.getKeyCode() + " = " + code
                        + "\nمن: " + (d == null ? "?" : d.getName())
                        + "   source=0x" + Integer.toHexString(event.getSource())
                        + "   repeat=" + event.getRepeatCount());
                refreshDevices();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        try {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                live("العصا: X=" + String.format("%.2f", event.getAxisValue(MotionEvent.AXIS_X))
                        + "  Y=" + String.format("%.2f", event.getAxisValue(MotionEvent.AXIS_Y))
                        + "  Z=" + String.format("%.2f", event.getAxisValue(MotionEvent.AXIS_Z))
                        + "  RZ=" + String.format("%.2f", event.getAxisValue(MotionEvent.AXIS_RZ))
                        + "  HAT_X=" + event.getAxisValue(MotionEvent.AXIS_HAT_X)
                        + "  HAT_Y=" + event.getAxisValue(MotionEvent.AXIS_HAT_Y));
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchView != null) {
                touchView.setText("لمسات الشاشة: " + Math.round(event.getX()) + ", "
                        + Math.round(event.getY()) + "   (المجموع: "
                        + (event.getPointerCount()) + " أصابع)");
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(event);
    }
}
