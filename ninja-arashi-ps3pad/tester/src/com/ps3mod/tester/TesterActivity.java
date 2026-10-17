package com.ps3mod.tester;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny (100 KB) answer to one question that has been impossible to settle from
 * the build machine:
 *
 *   1. which version of Ninja Arashi is actually installed on the phone,
 *   2. does Android hand this app the pad's keys at all,
 *   3. does the hidden InputManager.getKeyCodeState() reader work on Android 11
 *      with targetSdk 29 - the one API the whole v7/v8 design rests on.
 *
 * It installs next to the game (its own package), so it can never conflict with
 * the game's signature, and it prints everything in large text on one screen.
 */
public class TesterActivity extends Activity {

    private static final String GAME = "com.blackpanther.ninjaarashi";
    private static final String WANT_VERSION = "1.4-ps3v8";
    private static final String WANT_LAUNCHER = "com.ps3mod.PS3BootActivity";

    private TextView gameVerdict, gameDetail, padList, keyLine, keyDetail, readerLine, logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private StringBuilder log = new StringBuilder();
    private volatile boolean running = true;
    private String readerState = "…";

    private static final int[] CODES = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    };
    private final boolean[] last = new boolean[CODES.length];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF101010);
        int p = dp(12);
        col.setPadding(p, p, p, p);

        col.addView(title("PS3 TEST v8"));
        col.addView(small("هاد التطبيق كيقول ليك واش النسخة الجديدة ديال اللعبة مثبتة، وواش اليد كتوصل."));

        col.addView(title("1) اللعبة المثبتة"));
        gameVerdict = big("…", Color.WHITE);
        col.addView(gameVerdict);
        gameDetail = small("…");
        col.addView(gameDetail);

        col.addView(title("2) اليد"));
        padList = small("…");
        col.addView(padList);

        col.addView(title("3) آخر زر كتوصل لهاد التطبيق"));
        keyLine = big("—", Color.WHITE);
        col.addView(keyLine);
        keyDetail = small("اضغط أي زر فاليد دابا. إلا ما تبدل والو، اليد ما كتوصلش لهاد التطبيق.");
        col.addView(keyDetail);

        col.addView(title("4) القارئ المباشر (InputManager)"));
        readerLine = small("…");
        col.addView(readerLine);

        Button open = new Button(this);
        open.setText("▶ افتح Ninja Arashi");
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(GAME);
                    if (i == null) {
                        note("اللعبة ما مثبتةش");
                    } else {
                        startActivity(i);
                    }
                } catch (Throwable t) {
                    note("ما قدرتش نفتح: " + t);
                }
            }
        });
        col.addView(open);

        Button again = new Button(this);
        again.setText("⟳ تحديث المعلومات");
        again.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                readGame();
                readPads();
            }
        });
        col.addView(again);

        col.addView(title("سجل"));
        logView = small("");
        col.addView(logView);

        scroll.addView(col);
        setContentView(scroll);

        readGame();
        readPads();
        startReader();
        ui.postDelayed(new Runnable() {
            public void run() {
                readPads();
                if (running) {
                    ui.postDelayed(this, 3000L);
                }
            }
        }, 3000L);
    }

    /* ---------------------------------------------------------------- */

    private void readGame() {
        PackageManager pm = getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(GAME, 0);
            String launcher = "(none)";
            try {
                Intent i = pm.getLaunchIntentForPackage(GAME);
                if (i != null && i.getComponent() != null) {
                    launcher = i.getComponent().getClassName();
                }
            } catch (Throwable ignored) {
            }
            boolean vOk = WANT_VERSION.equals(pi.versionName);
            boolean lOk = WANT_LAUNCHER.equals(launcher);
            if (vOk && lOk) {
                gameVerdict.setText("✓ النسخة الجديدة مثبتة");
                gameVerdict.setTextColor(0xFF39FF6A);
            } else if (lOk) {
                gameVerdict.setText("⚠ المود مثبت ولكن النسخة مختلفة");
                gameVerdict.setTextColor(0xFFFFD54F);
            } else {
                gameVerdict.setText("✗ النسخة القديمة / بدون مود");
                gameVerdict.setTextColor(0xFFFF5252);
            }
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            gameDetail.setText("versionName: " + pi.versionName
                    + "\nالمتوقع: " + WANT_VERSION
                    + "\nlauncher: " + launcher
                    + "\nالمتوقع: " + WANT_LAUNCHER
                    + "\nآخر تحديث: " + f.format(new Date(pi.lastUpdateTime))
                    + "\nالحجم: " + (pi.applicationInfo.sourceDir == null ? "?" :
                    new java.io.File(pi.applicationInfo.sourceDir).length() / 1048576 + " MB"));
            note("game: versionName=" + pi.versionName + " launcher=" + launcher);
        } catch (PackageManager.NameNotFoundException e) {
            gameVerdict.setText("✗ Ninja Arashi ماشي مثبتة");
            gameVerdict.setTextColor(0xFFFF5252);
            gameDetail.setText("ما لقيتش " + GAME + " فهاد الهاتف.");
        } catch (Throwable t) {
            gameVerdict.setText("تعذر القراءة");
            gameDetail.setText(String.valueOf(t));
        }
    }

    private void readPads() {
        StringBuilder sb = new StringBuilder();
        int pads = 0;
        try {
            int[] ids = InputDevice.getDeviceIds();
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
                    sb.append("🎮 #").append(id).append(" ").append(d.getName())
                            .append(" [").append(Integer.toHexString(src)).append("]\n");
                }
            }
        } catch (Throwable t) {
            sb.append("خطأ: ").append(t).append("\n");
        }
        if (pads == 0) {
            sb.append("ما كايناش يد متصلة. وصّل الـ PS3 (بلوتوث) وعاود.");
        }
        padList.setText(sb.toString());
        padList.setTextColor(pads > 0 ? 0xFF39FF6A : 0xFFFF5252);
    }

    /* ---------------------------------------------------------------- */

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Class<?> im = Class.forName("android.hardware.input.InputManager");
                    final Object mgr = im.getMethod("getInstance").invoke(null);
                    final Method ids = im.getMethod("getInputDeviceIds");
                    final Method state = im.getMethod("getKeyCodeState",
                            int.class, int.class, int.class);
                    readerState = "✓ شغال";
                    ui.post(new Runnable() {
                        public void run() {
                            readerLine.setText("✓ شغال — هاد القارئ هو اللي كيخدم فالمود");
                            readerLine.setTextColor(0xFF39FF6A);
                        }
                    });
                    while (running) {
                        try {
                            int[] devs = (int[]) ids.invoke(mgr);
                            if (devs != null) {
                                for (int id : devs) {
                                    InputDevice d = InputDevice.getDevice(id);
                                    if (d == null) {
                                        continue;
                                    }
                                    int src = d.getSources();
                                    if ((src & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                                            && (src & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
                                        continue;
                                    }
                                    for (int k = 0; k < CODES.length; k++) {
                                        int st = (Integer) state.invoke(mgr, id, 0xffffff, CODES[k]);
                                        boolean down = st != 0;
                                        if (down != last[k]) {
                                            last[k] = down;
                                            showKey(CODES[k], down, "قارئ مباشر");
                                        }
                                    }
                                }
                            }
                        } catch (Throwable inside) {
                            // keep the thread alive, report once
                        }
                        try {
                            Thread.sleep(16L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                } catch (final Throwable t) {
                    readerState = "✗ " + t.getClass().getSimpleName();
                    ui.post(new Runnable() {
                        public void run() {
                            readerLine.setText("✗ ما قدرتش نقرا مباشرة: "
                                    + t.getClass().getSimpleName() + " — " + t.getMessage());
                            readerLine.setTextColor(0xFFFF5252);
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void showKey(final int code, final boolean down, final String from) {
        ui.post(new Runnable() {
            public void run() {
                String name = KeyEvent.keyCodeToString(code);
                keyLine.setText(name + " (" + code + ") " + (down ? "↓" : "↑"));
                keyLine.setTextColor(down ? 0xFF39FF6A : 0xFFFFD54F);
                note("key " + name + " " + (down ? "down" : "up") + " via " + from
                        + " at " + SystemClock.uptimeMillis());
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        String name = KeyEvent.keyCodeToString(ev.getKeyCode());
        keyDetail.setText("event: " + name + "\nkeyCode=" + ev.getKeyCode()
                + " scanCode=" + ev.getScanCode()
                + " device=" + ev.getDeviceId()
                + " source=0x" + Integer.toHexString(ev.getSource())
                + " action=" + ev.getAction());
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            showKey(ev.getKeyCode(), true, "event");
        }
        return true;   // keep the app's own focus, never let it fall through
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        try {
            float x = ev.getAxisValue(MotionEvent.AXIS_X)
                    + ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = ev.getAxisValue(MotionEvent.AXIS_Y)
                    + ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) > 0.05f || Math.abs(y) > 0.05f) {
                keyDetail.setText("stick: x=" + String.format(Locale.US, "%.2f", x)
                        + " y=" + String.format(Locale.US, "%.2f", y)
                        + "\ndevice=" + ev.getDeviceId()
                        + " source=0x" + Integer.toHexString(ev.getSource()));
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /* ---------------------------------------------------------------- */

    private void note(String s) {
        log.append(s).append('\n');
        if (log.length() > 4000) {
            log = new StringBuilder(log.substring(log.length() - 3000));
        }
        final String text = log.toString();
        ui.post(new Runnable() {
            public void run() {
                if (logView != null) {
                    logView.setText(text);
                }
            }
        });
    }

    private TextView title(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF64B5F6);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setPadding(0, dp(14), 0, dp(2));
        return tv;
    }

    private TextView big(String s, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        return tv;
    }

    private TextView small(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
