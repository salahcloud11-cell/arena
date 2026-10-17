package com.ps3mod;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DualShock 3 -> Ninja Arashi.
 *
 * Assumption this file is built on: Ninja Arashi is a touch-only game.  It has no
 * gamepad API and no gamepad code path, so the only way to drive it is with the
 * same input a finger produces on its on-screen buttons.  That is what this does:
 * pad state is converted into real MotionEvents (up to 10 pointers) and fed to
 * the activity, so the game cannot tell them apart from the developer's buttons.
 *
 * v3 changes (v2 was invisible / unresponsive on the device):
 *   - the calibration UI is a Dialog (its own window).  Drawn as a child of the
 *     activity's decor it sat *behind* Unity's SurfaceView, so nothing appeared.
 *   - the first pad button pressed in a session opens calibration, so it no
 *     longer depends on SELECT arriving (a DS3's SELECT is KEYCODE_BACK on some
 *     Android builds, KEYCODE_BUTTON_SELECT on others - both are handled).
 *   - a status line in the dialog reports what Android actually delivers, and a
 *     test button fires the last configured control so a position can be checked
 *     without leaving the screen.
 */
public class PS3Mapper {

    private static final String TAG = "PS3Mapper";
    private static final String VER = "v6";

    /* ------------------------------------------------------------------ */
    /* functions                                                          */
    /* ------------------------------------------------------------------ */
    private static final int F_MOVE = 0, F_JUMP = 1, F_ATTACK = 2, F_DASH = 3,
            F_TRANS = 4, F_FI = 5, F_PAUSE = 6, F_ALT = 7;
    private static final int FN = 8;

    private static final String[] SKEY = {"move", "jump", "attack", "dash",
            "transform", "throw", "pause", "alt"};
    private static final String[] LABEL = {
            "حركة (مركز العصا)", "قفز ✕", "هجوم □", "داش ○",
            "تحوّل L1", "رمي △", "إيقاف START", "اليمين (وضع الأزرار)"};

    /** First-run guesses; the calibration screen replaces them. */
    private static final float[] DX = {0.120f, 0.880f, 0.760f, 0.640f,
            0.520f, 0.880f, 0.950f, 0.760f};
    private static final float[] DY = {0.800f, 0.800f, 0.930f, 0.930f,
            0.930f, 0.600f, 0.070f, 0.700f};

    private static final boolean[] HOLD = {true, true, true, false,
            false, false, false, false};

    private static final float[] px = new float[FN];
    private static final float[] py = new float[FN];

    /* ------------------------------------------------------------------ */
    /* synthetic multi-touch engine                                       */
    /* ------------------------------------------------------------------ */
    private static final int MAX_POINTERS = 10;
    private static final boolean[] pActive = new boolean[MAX_POINTERS];
    private static final float[] pX = new float[MAX_POINTERS];
    private static final float[] pY = new float[MAX_POINTERS];
    private static int pCount = 0;
    private static long gestureDownTime = 0L;

    private static final long TAP_RELEASE_MS = 90L;
    private static final float STICK_DEADZONE = 0.28f;
    private static final float JOY_RADIUS = 0.075f;

    private static Handler handler = null;

    private static Handler handler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        return handler;
    }

    /* ------------------------------------------------------------------ */
    /* state                                                              */
    /* ------------------------------------------------------------------ */
    private static Activity activity = null;
    private static Dialog dialog = null;
    private static TextView statusView = null;
    private static boolean enabled = true;
    private static boolean calibrating = false;
    private static int captureIndex = -1;
    private static int lastCapture = -1;
    private static boolean joyMode = true;

    private static boolean dLeft = false, dRight = false, dUp = false, dDown = false;
    private static float axisX = 0f;
    private static boolean leftHeld = false, rightHeld = false, joyHeld = false;

    private static long lastPadEvent = 0L;
    private static boolean connectedToast = false;
    private static boolean autoOpened = false;
    private static long l3Down = 0L, r3Down = 0L;

    /* ------------------------------------------------------------------ */
    /* setup                                                              */
    /* ------------------------------------------------------------------ */
    public static void attach(Activity a) {
        if (a == null) {
            return;
        }
        activity = a;
        loadPositions(a);
    }

    private static int countPads() {
        int pads = 0;
        try {
            int[] ids = InputDevice.getDeviceIds();
            for (int i = 0; i < ids.length; i++) {
                int src = InputDevice.getDevice(ids[i]).getSources();
                if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                    pads++;
                }
            }
        } catch (Throwable ignored) {
        }
        return pads;
    }

    private static void reportPadStatus(Activity a) {
        int pads = countPads();
        if (pads == 0) {
            toast(a, "ما كايناش يد متصلة — وصّل الـ PS3 قبل ما تفتح اللعبة", Toast.LENGTH_LONG);
        } else {
            toast(a, "اليد متصلة ✓ (" + pads + ") — اضغط أي زر باش تفتح المعايرة", Toast.LENGTH_LONG);
        }
        updateStatus(null, -1);
    }

    private static void toast(Activity a, String msg) {
        toast(a, msg, Toast.LENGTH_LONG);
    }

    private static void toast(Activity a, String msg, int dur) {
        try {
            if (a != null) {
                Toast.makeText(a.getApplicationContext(), msg, dur).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void padSeen(Activity a) {
        lastPadEvent = SystemClock.uptimeMillis();
        if (!connectedToast) {
            connectedToast = true;
        }
    }

    private static void updateStatus(String key, int code) {
        final TextView tv = statusView;
        if (tv == null) {
            return;
        }
        final String text = "اليدات: " + countPads()
                + (key == null ? "" : "   |   آخر إشارة: " + key + " (" + code + ")");
        handler().post(new Runnable() {
            public void run() {
                try {
                    tv.setText(text);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* positions                                                          */
    /* ------------------------------------------------------------------ */
    private static void loadPositions(Activity a) {
        for (int i = 0; i < FN; i++) {
            px[i] = DX[i];
            py[i] = DY[i];
        }
        try {
            SharedPreferences sp = a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE);
            joyMode = sp.getBoolean("joy_mode", true);
            for (int i = 0; i < FN; i++) {
                px[i] = sp.getFloat("x_" + SKEY[i], DX[i]);
                py[i] = sp.getFloat("y_" + SKEY[i], DY[i]);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void savePositions(Activity a) {
        try {
            SharedPreferences.Editor e = a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE).edit();
            e.putBoolean("joy_mode", joyMode);
            for (int i = 0; i < FN; i++) {
                e.putFloat("x_" + SKEY[i], px[i]);
                e.putFloat("y_" + SKEY[i], py[i]);
            }
            e.putBoolean("calibrated", true);
            e.commit();
        } catch (Throwable ignored) {
        }
    }

    private static boolean calibrated(Activity a) {
        try {
            return a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE)
                    .getBoolean("calibrated", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Called from the hooked dispatchTouchEvent of the game's own activity.
     * Returns true only for touches the mapper handled itself (the calibration
     * entry tap and the three-corner-tap shortcut); everything else goes to the
     * game untouched.
     */
    public static boolean onTouch(Activity a, MotionEvent ev) {
        try {
            if (a == null || ev == null) {
                return false;
            }
            if (activity != a) {
                attach(a);
            }
            if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
                return false;
            }
            alive(a);
            if (!calibrated(a) && !autoOpened) {
                /* No pad involved: the first touch proves the mod is in control,
                 * so use it to open calibration. */
                autoOpened = true;
                showCalibration();
                return true;
            }
            return cornerTap(a, ev);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void alive(Activity a) {
        if (connectedToast) {
            return;
        }
        connectedToast = true;
        toast(a, "PS3 mod " + VER + " شغال ✓", Toast.LENGTH_SHORT);
        int pads = countPads();
        toast(a, pads == 0
                ? "ما كايناش يد متصلة — وصّل الـ PS3"
                : "اليد متصلة ✓ (" + pads + ") — اضغط أي زر فاليد", Toast.LENGTH_LONG);
        updateStatus(null, -1);
    }

    private static final long CORNER_TAP_WINDOW = 2500L;
    private static final float CORNER_FRACTION = 0.14f;
    private static int cornerCount = 0;
    private static long cornerFirst = 0L;

    private static boolean cornerTap(Activity a, MotionEvent ev) {
        float w = viewW(a);
        float h = viewH(a);
        if (ev.getX() > w * CORNER_FRACTION || ev.getY() > h * CORNER_FRACTION) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (cornerCount == 0 || now - cornerFirst > CORNER_TAP_WINDOW) {
            cornerCount = 1;
            cornerFirst = now;
        } else {
            cornerCount++;
        }
        if (cornerCount >= 3) {
            cornerCount = 0;
            showCalibration();
            return true;
        }
        return false;
    }

    public static boolean isCalibrating() {
        return calibrating;
    }

    public static void showCalibrationPublic() {
        showCalibration();
    }

    /** Opens calibration on any activity (used by the boot screen). */
    public static void openCalibration(Activity a) {
        if (a == null) {
            return;
        }
        activity = a;
        loadPositions(a);
        calibrated(a);          /* touch the prefs so defaults are loaded */
        autoOpened = true;      /* do not auto-open twice */
        showCalibration();
    }

    /* ------------------------------------------------------------------ */
    /* touch injection                                                    */
    /* ------------------------------------------------------------------ */
    private static int viewW(Activity a) {
        try {
            View v = a.getWindow().getDecorView();
            if (v.getWidth() > 0) {
                return v.getWidth();
            }
        } catch (Throwable ignored) {
        }
        return a.getResources().getDisplayMetrics().widthPixels;
    }

    private static int viewH(Activity a) {
        try {
            View v = a.getWindow().getDecorView();
            if (v.getHeight() > 0) {
                return v.getHeight();
            }
        } catch (Throwable ignored) {
        }
        return a.getResources().getDisplayMetrics().heightPixels;
    }

    private static void pushEvent(Activity a, int action, int actionPid) {
        int n = 0;
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (pActive[i]) {
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        int k = 0;
        int actionIndex = 0;
        for (int i = 0; i < MAX_POINTERS && k < n; i++) {
            if (!pActive[i]) {
                continue;
            }
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = i;
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[k] = pp;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = pX[i];
            pc.y = pY[i];
            pc.pressure = 1.0f;
            pc.size = 1.0f;
            coords[k] = pc;
            if (i == actionPid) {
                actionIndex = k;
            }
            k++;
        }
        int act = action;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            act = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = null;
        try {
            ev = MotionEvent.obtain(gestureDownTime, now, act, n, props, coords,
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            a.dispatchTouchEvent(ev);
        } catch (Throwable t) {
            Log.w(TAG, "touch dispatch failed", t);
        } finally {
            if (ev != null) {
                ev.recycle();
            }
        }
    }

    private static void ptrDown(Activity a, int pid, float x, float y) {
        if (pid < 0 || pid >= MAX_POINTERS) {
            return;
        }
        if (pActive[pid]) {
            pX[pid] = x;
            pY[pid] = y;
            if (pCount > 0) {
                pushEvent(a, MotionEvent.ACTION_MOVE, -1);
            }
            return;
        }
        if (pCount == 0) {
            gestureDownTime = SystemClock.uptimeMillis();
        }
        pActive[pid] = true;
        pX[pid] = x;
        pY[pid] = y;
        pCount++;
        pushEvent(a, pCount == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, pid);
    }

    private static void ptrMove(Activity a, int pid, float x, float y) {
        if (pid < 0 || pid >= MAX_POINTERS || !pActive[pid]) {
            return;
        }
        pX[pid] = x;
        pY[pid] = y;
        if (pCount > 0) {
            pushEvent(a, MotionEvent.ACTION_MOVE, -1);
        }
    }

    private static void ptrUp(Activity a, int pid) {
        if (pid < 0 || pid >= MAX_POINTERS || !pActive[pid]) {
            return;
        }
        pushEvent(a, pCount == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, pid);
        pActive[pid] = false;
        pCount--;
    }

    private static void releaseAll(Activity a) {
        for (int i = MAX_POINTERS - 1; i >= 0; i--) {
            if (pActive[i]) {
                ptrUp(a, i);
            }
        }
        leftHeld = false;
        rightHeld = false;
        joyHeld = false;
        tapGen++;
    }

    private static float fx(Activity a, int f) {
        return px[f] * viewW(a);
    }

    private static float fy(Activity a, int f) {
        return py[f] * viewH(a);
    }

    private static void pressFunc(Activity a, int f) {
        if (calibrating) {
            return;
        }
        ptrDown(a, f, fx(a, f), fy(a, f));
    }

    private static void releaseFunc(Activity a, int f) {
        ptrUp(a, f);
    }

    private static int tapGen = 0;

    private static void tapFunc(final Activity a, final int f) {
        if (calibrating) {
            return;
        }
        pressFunc(a, f);
        final int gen = tapGen;
        handler().postDelayed(new Runnable() {
            public void run() {
                if (gen == tapGen) {
                    releaseFunc(a, f);
                }
            }
        }, TAP_RELEASE_MS);
    }

    /* ------------------------------------------------------------------ */
    /* movement                                                           */
    /* ------------------------------------------------------------------ */
    private static float deadzone(float v) {
        float m = Math.abs(v);
        if (m < STICK_DEADZONE) {
            return 0f;
        }
        float s = (m - STICK_DEADZONE) / (1f - STICK_DEADZONE);
        if (s > 1f) {
            s = 1f;
        }
        return v < 0 ? -s : s;
    }

    private static void updateMove(Activity a) {
        float x = axisX;
        if (dLeft) {
            x -= 1f;
        }
        if (dRight) {
            x += 1f;
        }
        if (x > 1f) {
            x = 1f;
        }
        if (x < -1f) {
            x = -1f;
        }
        float mag = Math.abs(x);

        if (joyMode) {
            if (mag < 0.06f) {
                if (joyHeld) {
                    ptrUp(a, F_MOVE);
                    joyHeld = false;
                }
                return;
            }
            float cx = fx(a, F_MOVE);
            float cy = fy(a, F_MOVE);
            if (!joyHeld) {
                ptrDown(a, F_MOVE, cx, cy);
                joyHeld = true;
            }
            ptrMove(a, F_MOVE, cx + (x < 0 ? -1f : 1f) * mag * JOY_RADIUS * viewW(a), cy);
            return;
        }

        boolean wantLeft = x < -0.05f;
        boolean wantRight = x > 0.05f;
        if (wantLeft && rightHeld) {
            ptrUp(a, F_ALT);
            rightHeld = false;
        }
        if (wantRight && leftHeld) {
            ptrUp(a, F_MOVE);
            leftHeld = false;
        }
        if (wantLeft && !leftHeld) {
            pressFunc(a, F_MOVE);
            leftHeld = true;
        }
        if (wantRight && !rightHeld) {
            pressFunc(a, F_ALT);
            rightHeld = true;
        }
        if (!wantLeft && leftHeld) {
            ptrUp(a, F_MOVE);
            leftHeld = false;
        }
        if (!wantRight && rightHeld) {
            ptrUp(a, F_ALT);
            rightHeld = false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* device helpers                                                     */
    /* ------------------------------------------------------------------ */
    private static boolean isPad(int deviceId, int sources) {
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        try {
            InputDevice d = InputDevice.getDevice(deviceId);
            if (d != null) {
                int s = d.getSources();
                return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_BUTTON_SELECT
                || code == KeyEvent.KEYCODE_BUTTON_MODE;
    }

    private static int functionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return F_JUMP;
            case KeyEvent.KEYCODE_BUTTON_B:
                return F_DASH;
            case KeyEvent.KEYCODE_BUTTON_X:
                return F_ATTACK;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return F_FI;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return F_ATTACK;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return F_TRANS;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return F_DASH;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return F_TRANS;
            case KeyEvent.KEYCODE_BUTTON_START:
                return F_PAUSE;
            case KeyEvent.KEYCODE_DPAD_UP:
                return F_JUMP;
            default:
                return -1;
        }
    }

    /* ------------------------------------------------------------------ */
    /* pad events                                                         */
    /* ------------------------------------------------------------------ */
    public static boolean onKey(Activity a, KeyEvent ev) {
        if (a == null || ev == null) {
            return false;
        }
        try {
            if (activity != a) {
                attach(a);
            }
            int code = ev.getKeyCode();
            boolean pad = isPad(ev.getDeviceId(), ev.getSource());

            /* The phone's own back key is not ours unless a pad is involved. */
            if (code == KeyEvent.KEYCODE_BACK && !pad) {
                if (calibrating) {
                    hideCalibration();
                    return true;
                }
                return false;
            }
            if (!pad && !isDpad(code)) {
                return false;
            }
            if (ev.getRepeatCount() > 0) {
                return true;
            }
            padSeen(a);
            updateStatus(KeyEvent.keyCodeToString(code), code);

            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;

            /* Any pad button opens calibration the first time, so the screen can
             * be reached even if SELECT never arrives as KEYCODE_BACK. */
            if (!calibrated(a) && !autoOpened) {
                autoOpened = true;
                showCalibration();
                return true;
            }

            if (isSelect(code)) {
                if (!down) {
                    if (calibrating) {
                        hideCalibration();
                    } else {
                        showCalibration();
                    }
                }
                return true;
            }

            if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                l3Down = down ? SystemClock.uptimeMillis() : 0L;
                if (!down && r3Down > 0L && SystemClock.uptimeMillis() - r3Down < 800L) {
                    r3Down = 0L;
                    if (calibrating) {
                        hideCalibration();
                    } else {
                        showCalibration();
                    }
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                r3Down = down ? SystemClock.uptimeMillis() : 0L;
                return true;
            }

            if (calibrating) {
                return true;
            }

            if (isDpad(code)) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    dLeft = down;
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    dRight = down;
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    dUp = down;
                } else {
                    dDown = down;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (down) {
                        pressFunc(a, F_JUMP);
                    } else {
                        releaseFunc(a, F_JUMP);
                    }
                }
                updateMove(a);
                return true;
            }

            int f = functionFor(code);
            if (f < 0) {
                return false;
            }
            if (down) {
                if (HOLD[f]) {
                    pressFunc(a, f);
                } else {
                    tapFunc(a, f);
                }
            } else if (HOLD[f]) {
                releaseFunc(a, f);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "onKey failed", t);
            return false;
        }
    }

    public static boolean onMotion(Activity a, MotionEvent ev) {
        if (a == null || ev == null) {
            return false;
        }
        try {
            if (activity != a) {
                attach(a);
            }
            if (!isPad(ev.getDeviceId(), ev.getSource())) {
                return false;
            }
            if (ev.getAction() != MotionEvent.ACTION_MOVE) {
                return true;
            }
            padSeen(a);
            if (!enabled || calibrating) {
                return true;
            }
            float x = ev.getAxisValue(MotionEvent.AXIS_X)
                    + ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            if (x > 1f) {
                x = 1f;
            }
            if (x < -1f) {
                x = -1f;
            }
            axisX = deadzone(x);
            updateMove(a);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "onMotion failed", t);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* calibration dialog                                                 */
    /* ------------------------------------------------------------------ */
    private static float dp(Activity a, float v) {
        return v * a.getResources().getDisplayMetrics().density;
    }

    private static Button mkButton(final Activity a, String text, float size) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        b.setAllCaps(false);
        b.setPadding((int) dp(a, 8), (int) dp(a, 2), (int) dp(a, 8), (int) dp(a, 2));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    static void showCalibration() {
        final Activity a = activity;
        if (a == null || dialog != null) {
            return;
        }
        try {
            calibrating = true;
            releaseAll(a);

            final Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout root = new FrameLayout(a);
            root.setBackgroundColor(0x66000000);

            LinearLayout column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding((int) dp(a, 8), (int) dp(a, 8), (int) dp(a, 8), (int) dp(a, 8));
            column.setBackgroundColor(0xE6000000);

            final ScrollView scroll = new ScrollView(a);

            TextView title = new TextView(a);
            title.setText("معايرة PS3 " + VER
                    + "\n١) اضغط زرً من القائمة  ٢) المس مكان زرّ اللعبة على الشاشة\nثم «حفظ وإغلاق».");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            column.addView(title);

            statusView = new TextView(a);
            statusView.setTextColor(0xFF7CFF7C);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            statusView.setText("اليدات: " + countPads() + "   |   آخر إشارة: —");
            column.addView(statusView);

            final View captureLayer = new View(a);
            captureLayer.setVisibility(View.GONE);

            for (int i = 0; i < FN; i++) {
                final int f = i;
                Button b = mkButton(a, LABEL[i], 11f);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        captureIndex = f;
                        lastCapture = f;
                        scroll.setVisibility(View.GONE);
                        captureLayer.setVisibility(View.VISIBLE);
                        captureLayer.bringToFront();
                        toast(a, "المس مكان: " + LABEL[f], Toast.LENGTH_SHORT);
                    }
                });
                column.addView(b);
            }

            final Button modeBtn = mkButton(a, joyMode
                    ? "الحركة: سحب عصا ✔ (اضغط للتبديل)"
                    : "الحركة: زر ثابت (اضغط للتبديل)", 11f);
            modeBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    joyMode = !joyMode;
                    modeBtn.setText(joyMode
                            ? "الحركة: سحب عصا ✔ (اضغط للتبديل)"
                            : "الحركة: زر ثابت (اضغط للتبديل)");
                }
            });
            column.addView(modeBtn);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button test = mkButton(a, "تجربة آخر زر", 11f);
            test.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final int f = lastCapture >= 0 ? lastCapture : F_JUMP;
                    toast(a, "تجربة: " + LABEL[f], Toast.LENGTH_SHORT);
                    boolean wasCal = calibrating;
                    calibrating = false;          /* allow the test injection */
                    pressFunc(a, f);
                    handler().postDelayed(new Runnable() {
                        public void run() {
                            releaseFunc(a, f);
                        }
                    }, 220L);
                    calibrating = wasCal;
                }
            });
            row.addView(test);

            Button save = mkButton(a, "حفظ وإغلاق", 11f);
            save.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    savePositions(a);
                    hideCalibration();
                    toast(a, "تم حفظ المعايرة ✓", Toast.LENGTH_SHORT);
                }
            });
            row.addView(save);
            column.addView(row);

            Button close = mkButton(a, "إغلاق بلا حفظ", 10f);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    hideCalibration();
                }
            });
            column.addView(close);

            scroll.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    (int) dp(a, 300), ViewGroup.LayoutParams.MATCH_PARENT);
            slp.gravity = Gravity.LEFT | Gravity.TOP;
            root.addView(scroll, slp);
            root.addView(captureLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            captureLayer.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN && captureIndex >= 0) {
                        int w = v.getWidth() > 0 ? v.getWidth() : viewW(a);
                        int h = v.getHeight() > 0 ? v.getHeight() : viewH(a);
                        px[captureIndex] = e.getX() / w;
                        py[captureIndex] = e.getY() / h;
                        captureIndex = -1;
                        captureLayer.setVisibility(View.GONE);
                        scroll.setVisibility(View.VISIBLE);
                        return true;
                    }
                    return true;
                }
            });

            d.setContentView(root);
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0.35f;
                w.setAttributes(lp);
            }
            d.setOnKeyListener(new Dialog.OnKeyListener() {
                public boolean onKey(android.content.DialogInterface di, int code, KeyEvent e) {
                    if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_SELECT)
                            && e.getAction() == KeyEvent.ACTION_UP) {
                        hideCalibration();
                        return true;
                    }
                    return false;
                }
            });
            d.show();
            dialog = d;
            Log.i(TAG, "calibration dialog shown");
        } catch (Throwable t) {
            Log.w(TAG, "calibration dialog failed", t);
            dialog = null;
            calibrating = false;
            toast(a, "ما قدرتش نفتح شاشة المعايرة: " + t.getClass().getSimpleName());
        }
    }

    private static void hideCalibration() {
        calibrating = false;
        captureIndex = -1;
        statusView = null;
        try {
            Dialog d = dialog;
            dialog = null;
            if (d != null) {
                d.dismiss();
            }
        } catch (Throwable ignored) {
        }
        Activity a = activity;
        if (a != null) {
            releaseAll(a);
        }
        Log.i(TAG, "calibration closed");
    }
}
