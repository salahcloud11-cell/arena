package com.ps3mod;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    private static final String VER = "v10";

    /* ------------------------------------------------------------------ */
    /* functions                                                          */
    /* ------------------------------------------------------------------ */
    /* Every physical control has its own editable screen target.  The last
     * five entries are user-created buttons, enabled with "+ ADD BUTTON". */
    private static final int F_MOVE = 0, F_R_STICK = 1, F_X = 2, F_CIRCLE = 3,
            F_SQUARE = 4, F_TRIANGLE = 5, F_L1 = 6, F_R1 = 7, F_L2 = 8,
            F_R2 = 9, F_START = 10, F_SELECT = 11, F_L3 = 12, F_R3 = 13,
            F_DLEFT = 14, F_DRIGHT = 15, F_DUP = 16, F_DDOWN = 17,
            F_CUSTOM1 = 18;
    private static final int FN = 23;

    private static final String[] SKEY = {"left_stick", "right_stick", "cross",
            "circle", "square", "triangle", "l1", "r1", "l2", "r2",
            "start", "select", "l3", "r3", "dleft", "dright", "dup", "ddown",
            "custom1", "custom2", "custom3", "custom4", "custom5"};
    private static final String[] LABEL = {"LEFT STICK / الحركة", "RIGHT STICK",
            "✕ CROSS", "○ CIRCLE", "□ SQUARE", "△ TRIANGLE", "L1", "R1",
            "L2", "R2", "START", "SELECT", "L3", "R3", "D-PAD LEFT",
            "D-PAD RIGHT", "D-PAD UP", "D-PAD DOWN", "CUSTOM 1", "CUSTOM 2",
            "CUSTOM 3", "CUSTOM 4", "CUSTOM 5"};
    private static final String[] SHORT = {"L-STICK", "R-STICK", "✕", "○", "□", "△",
            "L1", "R1", "L2", "R2", "START", "SELECT", "L3", "R3", "◀", "▶",
            "▲", "▼", "+1", "+2", "+3", "+4", "+5"};

    /** Safe first-run positions; every one can be moved in the editor. */
    private static final float[] DX = {0.13f,0.82f,0.88f,0.94f,0.76f,0.88f,
            0.08f,0.92f,0.16f,0.84f,0.56f,0.44f,0.25f,0.75f,0.06f,0.18f,
            0.12f,0.12f,0.35f,0.45f,0.55f,0.65f,0.75f};
    private static final float[] DY = {0.78f,0.72f,0.80f,0.68f,0.68f,0.56f,
            0.08f,0.08f,0.08f,0.08f,0.08f,0.08f,0.88f,0.88f,0.78f,0.78f,
            0.68f,0.88f,0.50f,0.50f,0.50f,0.50f,0.50f};
    private static final int[] DEFAULT_KEYS = {-1, -2, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0,0,0,0,0};

    private static final float[] px = new float[FN];
    private static final float[] py = new float[FN];
    private static final int[] mappedKey = new int[FN];
    private static final int[] clickCount = new int[FN];
    private static final boolean[] slotEnabled = new boolean[FN];

    /* ------------------------------------------------------------------ */
    /* synthetic multi-touch engine                                       */
    /* ------------------------------------------------------------------ */
    private static final int MAX_POINTERS = 24;
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
    private static int captureKeyIndex = -1;
    private static int lastCapture = -1;
    private static TextView captureKeyView = null;
    private static boolean joyMode = true;

    private static boolean dLeft = false, dRight = false, dUp = false, dDown = false;
    private static float axisX = 0f;
    private static float rightX = 0f, rightY = 0f;
    private static boolean rightStickHeld = false, l2AxisHeld = false, r2AxisHeld = false;
    private static boolean leftHeld = false, rightHeld = false, joyHeld = false;

    private static long lastPadEvent = 0L;
    private static int lastRealCode = -1;
    private static boolean lastRealDown = false;
    private static long lastRealAt = 0L;
    private static int lastPollCode = -1;
    private static boolean lastPollDown = false;
    private static long lastPollAt = 0L;
    private static volatile boolean polling = false;
    private static Object inputManager = null;
    private static java.lang.reflect.Method mGetIds = null;
    private static java.lang.reflect.Method mGetKeyState = null;
    private static final boolean[] pollKeys = new boolean[19];
    private static final int[] POLL_CODES = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_C};
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
            mappedKey[i] = DEFAULT_KEYS[i];
            clickCount[i] = 1;
            slotEnabled[i] = i < F_CUSTOM1;
        }
        try {
            SharedPreferences sp = a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE);
            joyMode = sp.getBoolean("joy_mode", true);
            previewShown = sp.getBoolean("preview_shown", false);
            for (int i = 0; i < FN; i++) {
                px[i] = sp.getFloat("x_" + SKEY[i], DX[i]);
                py[i] = sp.getFloat("y_" + SKEY[i], DY[i]);
                mappedKey[i] = sp.getInt("key_" + SKEY[i], DEFAULT_KEYS[i]);
                clickCount[i] = Math.max(1, Math.min(5,
                        sp.getInt("clicks_" + SKEY[i], 1)));
                slotEnabled[i] = sp.getBoolean("enabled_" + SKEY[i], i < F_CUSTOM1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void savePositions(Activity a) {
        try {
            SharedPreferences.Editor e = a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE).edit();
            e.putBoolean("joy_mode", joyMode);
            e.putBoolean("preview_shown", previewShown);
            for (int i = 0; i < FN; i++) {
                e.putFloat("x_" + SKEY[i], px[i]);
                e.putFloat("y_" + SKEY[i], py[i]);
                e.putInt("key_" + SKEY[i], mappedKey[i]);
                e.putInt("clicks_" + SKEY[i], clickCount[i]);
                e.putBoolean("enabled_" + SKEY[i], slotEnabled[i]);
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
            startPolling();
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

    /**
     * Called from the hooked onCreate/onResume of the game's activity.
     *
     * Starts the polling reader. Reading the pad through InputManager works even
     * when every key/motion event is swallowed by Unity, which is the one thing
     * that could never be confirmed from here without a device.
     */
    public static void onGameActivity(Activity a) {
        if (a == null) {
            return;
        }
        attach(a);
        alive(a);
        startPolling();
        installOverlay(a);
        maybeFirstRun(a);
    }

    /**
     * The first launch of this build opens the calibration by itself.  Every
     * earlier build relied on a pad key or on the prefs saying "not calibrated
     * yet"; both could silently be false on the phone, which is exactly the
     * report that kept coming back.  A once-per-build flag cannot be false.
     */
    private static void maybeFirstRun(final Activity a) {
        try {
            SharedPreferences sp = a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE);
            if (sp.getBoolean("seen_" + VER, false) || firstRunScheduled) {
                return;
            }
            firstRunScheduled = true;
            final Runnable open = new Runnable() {
                public void run() {
                    if (!calibrating) {
                        showCalibration();
                    }
                }
            };
            handler().postDelayed(open, 2500L);
            handler().postDelayed(open, 9000L);
        } catch (Throwable ignored) {
        }
    }

    private static void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Class<?> im = Class.forName("android.hardware.input.InputManager");
                    Object mgr = im.getMethod("getInstance").invoke(null);
                    java.lang.reflect.Method ids = im.getMethod("getInputDeviceIds");
                    java.lang.reflect.Method state = im.getMethod("getKeyCodeState",
                            int.class, int.class, int.class);
                    inputManager = mgr;
                    mGetIds = ids;
                    mGetKeyState = state;
                    readerText = "مباشر ✓";
                    refreshChip(null);
                    Log.i(TAG, "polling reader started");
                } catch (Throwable t2) {
                    polling = false;
                    readerText = "محجوب ✗";
                    refreshChip(null);
                    Log.w(TAG, "polling reader unavailable", t2);
                    return;
                }
                while (polling) {
                    try {
                        pollOnce();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(16L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void pollOnce() {
        final Activity a = activity;
        if (a == null || inputManager == null) {
            return;
        }
        int[] ids;
        try {
            ids = (int[]) mGetIds.invoke(inputManager);
        } catch (Throwable t) {
            return;
        }
        if (ids == null) {
            return;
        }
        boolean[] now = new boolean[POLL_CODES.length];
        boolean found = false;
        for (int id : ids) {
            InputDevice d = null;
            try {
                d = InputDevice.getDevice(id);
            } catch (Throwable ignored) {
            }
            if (d == null) {
                continue;
            }
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                    && (src & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
                continue;
            }
            found = true;
            for (int k = 0; k < POLL_CODES.length; k++) {
                try {
                    int st = (Integer) mGetKeyState.invoke(inputManager, id, 0xffffff,
                            POLL_CODES[k]);
                    if (st != 0) {
                        now[k] = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (!found) {
            return;
        }
        /* Digital key state, independent of event delivery. */
        for (int k = 0; k < POLL_CODES.length; k++) {
            if (now[k] == pollKeys[k]) {
                continue;
            }
            pollKeys[k] = now[k];
            final int code = POLL_CODES[k];
            final boolean down = now[k];
            handler().post(new Runnable() {
                public void run() {
                    pollKey(a, code, down);
                }
            });
        }
    }

    /** Applies a polled key transition through the same handlers events use. */
    private static void pollKey(Activity a, int code, boolean down) {
        try {
            refreshChip(KeyEvent.keyCodeToString(code) + " " + code
                    + (down ? " ↓" : " ↑"));
            if (activity == null) {
                activity = a;
                loadPositions(a);
            }
            /* De-duplicate only the exact transition that the Activity just
             * delivered. The old two-second global suppression could discard a
             * polled key-UP and leave LEFT held forever. */
            if (code == lastRealCode && down == lastRealDown
                    && SystemClock.uptimeMillis() - lastRealAt < 180L) {
                return;
            }
            if (calibrating) {
                if (down && captureKeyIndex >= 2) {
                    assignCapturedKey(a, captureKeyIndex, code);
                }
                return;
            }
            int f = functionFor(code);
            if (f < 0) {
                return;
            }
            lastPollCode = code;
            lastPollDown = down;
            lastPollAt = SystemClock.uptimeMillis();
            activateFunc(a, f, down);
        } catch (Throwable t) {
            Log.w(TAG, "pollKey failed", t);
        }
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
        rightStickHeld = false;
        l2AxisHeld = false;
        r2AxisHeld = false;
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

    /** One target can generate 1..5 taps from one physical press. */
    private static void activateFunc(final Activity a, final int f, boolean down) {
        int clicks = (f >= 0 && f < FN) ? clickCount[f] : 1;
        if (clicks <= 1) {
            if (down) {
                pressFunc(a, f);
            } else {
                releaseFunc(a, f);
            }
            return;
        }
        if (!down || calibrating) {
            return;
        }
        final int gen = tapGen;
        for (int i = 0; i < clicks; i++) {
            final long start = i * 125L;
            handler().postDelayed(new Runnable() {
                public void run() {
                    if (gen == tapGen) {
                        pressFunc(a, f);
                    }
                }
            }, start);
            handler().postDelayed(new Runnable() {
                public void run() {
                    if (gen == tapGen) {
                        releaseFunc(a, f);
                    }
                }
            }, start + 60L);
        }
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
            ptrUp(a, F_DRIGHT);
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
            pressFunc(a, F_DRIGHT);
            rightHeld = true;
        }
        if (!wantLeft && leftHeld) {
            ptrUp(a, F_MOVE);
            leftHeld = false;
        }
        if (!wantRight && rightHeld) {
            ptrUp(a, F_DRIGHT);
            rightHeld = false;
        }
    }

    private static void updateRightStick(Activity a) {
        float x = deadzone(rightX);
        float y = deadzone(rightY);
        float mag = Math.max(Math.abs(x), Math.abs(y));
        if (mag < 0.06f) {
            if (rightStickHeld) {
                ptrUp(a, F_R_STICK);
                rightStickHeld = false;
            }
            return;
        }
        float cx = fx(a, F_R_STICK);
        float cy = fy(a, F_R_STICK);
        if (!rightStickHeld) {
            ptrDown(a, F_R_STICK, cx, cy);
            rightStickHeld = true;
        }
        ptrMove(a, F_R_STICK, cx + x * JOY_RADIUS * viewW(a),
                cy + y * JOY_RADIUS * viewH(a));
    }

    private static void axisButton(Activity a, int f, boolean down, boolean leftTrigger) {
        boolean was = leftTrigger ? l2AxisHeld : r2AxisHeld;
        if (down == was) {
            return;
        }
        if (leftTrigger) {
            l2AxisHeld = down;
        } else {
            r2AxisHeld = down;
        }
        activateFunc(a, f, down);
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
        for (int i = 2; i < FN; i++) {
            if (slotEnabled[i] && mappedKey[i] == keyCode) {
                return i;
            }
        }
        return -1;
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
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            lastRealCode = code;
            lastRealDown = down;
            lastRealAt = SystemClock.uptimeMillis();
            padSeen(a);
            updateStatus(KeyEvent.keyCodeToString(code), code);
            refreshChip(KeyEvent.keyCodeToString(code) + " " + code + (down ? " ↓" : " ↑"));

            /* While "CHANGE" is armed, the next physical button becomes this
             * screen target. Nothing is hardwired, including SELECT and D-pad. */
            if (calibrating) {
                if (down && captureKeyIndex >= 2) {
                    assignCapturedKey(a, captureKeyIndex, code);
                }
                return true;
            }

            int f = functionFor(code);
            if (f < 0) {
                return false;
            }
            if (code == lastPollCode && down == lastPollDown
                    && SystemClock.uptimeMillis() - lastPollAt < 180L) {
                return true;
            }
            activateFunc(a, f, down);
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

            rightX = ev.getAxisValue(MotionEvent.AXIS_RX);
            rightY = ev.getAxisValue(MotionEvent.AXIS_RY);
            updateRightStick(a);

            /* Many Android DS3 drivers expose L2/R2 only as Z/RZ, with no key
             * event. They still use the user's editable L2/R2 screen targets. */
            axisButton(a, F_L2, ev.getAxisValue(MotionEvent.AXIS_Z) > 0.35f, true);
            axisButton(a, F_R2, ev.getAxisValue(MotionEvent.AXIS_RZ) > 0.35f, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "onMotion failed", t);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* the badge drawn over the game                                      */
    /*                                                                     */
    /* Nothing so far could prove, from here, that this code runs at all.  */
    /* A Toast can be missed and a Dialog can fail to open, but this badge */
    /* is drawn into the game's own window: if the mod is running it is    */
    /* on screen from the first second, and it prints every key Android    */
    /* delivers - events and the InputManager reader separately.           */
    /* ------------------------------------------------------------------ */
    private static View overlayRoot = null;
    private static TextView chipPads = null;
    private static TextView chipKey = null;
    private static TextView chipReader = null;
    private static FrameLayout buttonPreview = null;
    /* Target circles are invisible during normal play by default. */
    private static boolean previewShown = false;
    private static String lastKeyText = "—";
    private static String readerText = "…";
    private static boolean badgeScheduled = false;
    private static boolean firstRunScheduled = false;

    private static int di(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Shows the badge, or brings it back after the game re-created its window. */
    private static void installOverlay(final Activity a) {
        if (a == null) {
            return;
        }
        final Activity act = a;
        Runnable add = new Runnable() {
            public void run() {
                addBadge(act);
            }
        };
        handler().post(add);
        if (!badgeScheduled) {
            badgeScheduled = true;
            /* Late enough that the game has installed its own content view, and
             * twice more for the splash screens that replace it. */
            handler().postDelayed(add, 1500L);
            handler().postDelayed(add, 6000L);
            handler().postDelayed(add, 20000L);
        }
    }

    private static void addBadge(final Activity a) {
        try {
            if (overlayRoot != null && overlayRoot.getParent() != null
                    && overlayRoot.getWindowToken() != null) {
                overlayRoot.setVisibility(View.VISIBLE);
                refreshChip(null);
                return;
            }
            overlayRoot = null;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC000000);
            bg.setCornerRadius(di(a, 5));
            bg.setStroke(di(a, 1), 0xFF39FF6A);
            box.setBackground(bg);
            box.setPadding(di(a, 6), di(a, 2), di(a, 6), di(a, 3));

            chipPads = chipLine(a, "PS3 " + VER + " · 🎮 …", 0xFF39FF6A, 9f);
            chipKey = chipLine(a, "آخر: —", 0xFFFFFFFF, 9f);
            chipReader = chipLine(a, "قارئ: …", 0xFFFFD54F, 8f);
            box.addView(chipPads);
            box.addView(chipKey);
            box.addView(chipReader);

            LinearLayout bar = new LinearLayout(a);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            Button customize = mkButton(a, "🎯 CUSTOMIZE", 8f);
            customize.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openCalibration(a);
                }
            });
            bar.addView(customize);
            final Button visible = mkButton(a,
                    previewShown ? "👁 HIDE TARGETS" : "👁 SHOW TARGETS", 8f);
            visible.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    previewShown = !previewShown;
                    visible.setText(previewShown ? "👁 HIDE TARGETS" : "👁 SHOW TARGETS");
                    savePositions(a);
                    drawButtonPreview(a);
                }
            });
            bar.addView(visible);
            box.addView(bar);

            box.setClickable(false);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = di(a, 3);
            lp.topMargin = di(a, 26);
            a.addContentView(box, lp);
            overlayRoot = box;
            drawButtonPreview(a);
            refreshChip(null);
            Log.i(TAG, "badge installed over the game");
        } catch (Throwable t) {
            Log.w(TAG, "badge failed", t);
        }
    }

    /** Draws a transparent, non-touchable map of every enabled PS3 target. */
    private static void drawButtonPreview(Activity a) {
        try {
            if (buttonPreview != null && buttonPreview.getParent() instanceof ViewGroup) {
                ((ViewGroup) buttonPreview.getParent()).removeView(buttonPreview);
            }
            buttonPreview = null;
            if (!previewShown) {
                return;
            }
            final FrameLayout layer = new FrameLayout(a);
            layer.setClickable(false);
            layer.setFocusable(false);
            for (int i = 0; i < FN; i++) {
                if (!slotEnabled[i]) {
                    continue;
                }
                TextView m = new TextView(a);
                m.setText(SHORT[i]);
                m.setTextColor(0xEEFFFFFF);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
                m.setGravity(Gravity.CENTER);
                m.setClickable(false);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0x44000000);
                g.setStroke(di(a, 1), 0xAA00FF88);
                m.setBackground(g);
                m.setPadding(di(a, 4), di(a, 4), di(a, 4), di(a, 4));
                FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                layer.addView(m, mp);
                final int f = i;
                final TextView mark = m;
                layer.post(new Runnable() {
                    public void run() {
                        int w = layer.getWidth();
                        int h = layer.getHeight();
                        mark.setX(px[f] * w - mark.getWidth() / 2f);
                        mark.setY(py[f] * h - mark.getHeight() / 2f);
                    }
                });
            }
            a.addContentView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            buttonPreview = layer;
            if (overlayRoot != null) {
                overlayRoot.bringToFront();
            }
        } catch (Throwable t) {
            Log.w(TAG, "button preview failed", t);
        }
    }

    private static TextView chipLine(Activity a, String text, int color, float size) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setSingleLine(true);
        return tv;
    }

    /** Repaints the badge; safe to call from the reader thread. */
    private static void refreshChip(final String key) {
        if (key != null) {
            lastKeyText = key;
        }
        final Activity a = activity;
        if (a == null) {
            return;
        }
        handler().post(new Runnable() {
            public void run() {
                try {
                    if (chipPads == null) {
                        return;
                    }
                    chipPads.setText("PS3 " + VER + " · 🎮 " + countPads()
                            + (calibrating ? " · معايرة" : ""));
                    chipKey.setText("آخر: " + lastKeyText);
                    chipReader.setText("قارئ: " + readerText);
                } catch (Throwable ignored) {
                }
            }
        });
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

    private static String keyName(int code) {
        if (code <= 0) {
            return "اضغط CHANGE";
        }
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_BUTTON_", "").replace("KEYCODE_", "");
    }

    /** Completes "CHANGE": one physical key owns one screen target. */
    private static void assignCapturedKey(Activity a, int f, int code) {
        if (f < 2 || f >= FN) {
            return;
        }
        for (int i = 2; i < FN; i++) {
            if (i != f && mappedKey[i] == code) {
                mappedKey[i] = 0;
            }
        }
        mappedKey[f] = code;
        slotEnabled[f] = true;
        captureKeyIndex = -1;
        if (captureKeyView != null) {
            captureKeyView.setText(keyName(code));
        }
        if (statusView != null) {
            statusView.setText("✓ " + LABEL[f] + " = " + keyName(code));
        }
        savePositions(a);
        toast(a, LABEL[f] + " ← " + keyName(code), Toast.LENGTH_SHORT);
    }

    /* ------------------------------------------------------------------ */
    /* placement editor: drag each marker onto the game's own button        */
    /* ------------------------------------------------------------------ */
    private static View placer = null;
    private static final TextView[] marks = new TextView[FN];

    private static void showPlacer(final Activity a, final FrameLayout root,
                                   final ScrollView scroll, final View captureLayer) {
        try {
            if (placer != null) {
                return;
            }
            final FrameLayout layer = new FrameLayout(a);
            layer.setBackgroundColor(0x11000000);

            TextView hint = new TextView(a);
            hint.setText("اسحب كل دائرة فوق زرّ اللعبة اللي تحتها، ومن بعد «حفظ»");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            hint.setBackgroundColor(0xCC000000);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            hlp.topMargin = di(a, 4);
            layer.addView(hint, hlp);

            Button save = mkButton(a, "حفظ ✓", 12f);
            save.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    savePositions(a);
                    hideCalibration();
                    toast(a, "تم الحفظ ✓ — جرّب اليد دابا", Toast.LENGTH_LONG);
                }
            });
            FrameLayout.LayoutParams slp2 = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp2.gravity = Gravity.RIGHT | Gravity.TOP;
            layer.addView(save, slp2);

            Button back = mkButton(a, "رجوع", 11f);
            back.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    hidePlacer(root, scroll, captureLayer);
                }
            });
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.LEFT | Gravity.TOP;
            layer.addView(back, blp);

            for (int i = 0; i < FN; i++) {
                final int f = i;
                TextView m = new TextView(a);
                m.setText(SHORT[i]);
                m.setGravity(Gravity.CENTER);
                m.setTextColor(Color.WHITE);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0xCC000000 | ((i * 0x001B3A2C + 0x0000FF00) & 0x00FFFFFF));
                g.setStroke(di(a, 1), 0xFF00FF88);
                m.setBackground(g);
                m.setPadding(di(a, 6), di(a, 6), di(a, 6), di(a, 6));
                m.setOnTouchListener(new View.OnTouchListener() {
                    float dx = 0f, dy = 0f;

                    public boolean onTouch(View v, MotionEvent e) {
                        int act = e.getActionMasked();
                        if (act == MotionEvent.ACTION_DOWN) {
                            dx = e.getRawX() - v.getX();
                            dy = e.getRawY() - v.getY();
                            v.bringToFront();
                            return true;
                        }
                        if (act == MotionEvent.ACTION_MOVE) {
                            float nxp = e.getRawX() - dx;
                            float nyp = e.getRawY() - dy;
                            v.setX(nxp);
                            v.setY(nyp);
                            int w = layer.getWidth() > 0 ? layer.getWidth() : viewW(a);
                            int h = layer.getHeight() > 0 ? layer.getHeight() : viewH(a);
                            px[f] = clamp01((nxp + v.getWidth() / 2f) / w);
                            py[f] = clamp01((nyp + v.getHeight() / 2f) / h);
                            return true;
                        }
                        return true;
                    }
                });
                FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                layer.addView(m, mlp);
                m.setVisibility(slotEnabled[i] ? View.VISIBLE : View.GONE);
                marks[i] = m;
            }

            layer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    placeMarks(layer);
                }
            });
            layer.post(new Runnable() {
                public void run() {
                    placeMarks(layer);
                }
            });

            root.addView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layer.bringToFront();
            scroll.setVisibility(View.GONE);
            captureLayer.setVisibility(View.GONE);
            placer = layer;
            toast(a, "اسحب فوق أزرار اللعبة", Toast.LENGTH_SHORT);
        } catch (Throwable t) {
            Log.w(TAG, "placer failed", t);
        }
    }

    private static float clamp01(float v) {
        return v < 0.01f ? 0.01f : (v > 0.99f ? 0.99f : v);
    }

    private static void placeMarks(FrameLayout layer) {
        try {
            int w = layer.getWidth();
            int h = layer.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            for (int i = 0; i < FN; i++) {
                TextView m = marks[i];
                if (m == null) {
                    continue;
                }
                m.setX(px[i] * w - m.getWidth() / 2f);
                m.setY(py[i] * h - m.getHeight() / 2f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hidePlacer(FrameLayout root, ScrollView scroll, View captureLayer) {
        try {
            if (placer != null) {
                root.removeView(placer);
                placer = null;
            }
            for (int i = 0; i < FN; i++) {
                marks[i] = null;
            }
            captureLayer.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
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
            root.setBackgroundColor(0x22000000);

            LinearLayout column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding((int) dp(a, 8), (int) dp(a, 8), (int) dp(a, 8), (int) dp(a, 8));
            column.setBackgroundColor(0xE6000000);

            final ScrollView scroll = new ScrollView(a);

            TextView title = new TextView(a);
            title.setText("PS3 CONTROL STUDIO " + VER
                    + "\n📍 PLACE = مكان اللمس   |   CHANGE = زر اليد   |   1×..5× = عدد النقرات"
                    + "\nكل الإعدادات كتتحفظ. دوائر اللمس يمكن إخفاؤها أثناء اللعب.");
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

            /* Finger-only path: no pad, no SELECT, no key of any kind.  The game
             * stays visible under the dialog so each marker can be dropped onto
             * the developer's own on-screen button. */
            Button placerBtn = mkButton(a, "🎯 ضع الأزرار بالمسح (بلا يد)", 12f);
            placerBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showPlacer(a, root, scroll, captureLayer);
                }
            });
            column.addView(placerBtn);

            final Button visibilityBtn = mkButton(a, previewShown
                    ? "👁 TARGETS VISIBLE — MAKE INVISIBLE"
                    : "◉ TARGETS INVISIBLE — SHOW", 10f);
            visibilityBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    previewShown = !previewShown;
                    visibilityBtn.setText(previewShown
                            ? "👁 TARGETS VISIBLE — MAKE INVISIBLE"
                            : "◉ TARGETS INVISIBLE — SHOW");
                    savePositions(a);
                    drawButtonPreview(a);
                }
            });
            column.addView(visibilityBtn);

            final Button[] positionButtons = new Button[FN];
            final Button[] changeButtons = new Button[FN];
            final Button[] clickButtons = new Button[FN];
            for (int i = 0; i < FN; i++) {
                if (i == 0 || i == 2 || i == 6 || i == 10 || i == 14 || i == F_CUSTOM1) {
                    TextView section = new TextView(a);
                    String sectionName = i == 0 ? "── ANALOG STICKS ──"
                            : i == 2 ? "── FACE BUTTONS ──"
                            : i == 6 ? "── SHOULDERS / TRIGGERS ──"
                            : i == 10 ? "── START / SELECT / STICK CLICKS ──"
                            : i == 14 ? "── D-PAD ──" : "── EXTRA BUTTONS ──";
                    section.setText(sectionName);
                    section.setTextColor(0xFF64FF91);
                    section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                    section.setPadding(0, (int) dp(a, 5), 0, (int) dp(a, 2));
                    column.addView(section);
                }
                final int f = i;
                LinearLayout controlRow = new LinearLayout(a);
                controlRow.setOrientation(LinearLayout.HORIZONTAL);
                Button b = mkButton(a, "📍 " + LABEL[i], 9f);
                b.setEnabled(slotEnabled[i]);
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
                controlRow.addView(b, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                positionButtons[i] = b;

                Button change = mkButton(a, f < 2 ? "ANALOG" : keyName(mappedKey[i]), 8f);
                change.setEnabled(slotEnabled[i] && f >= 2);
                change.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        captureKeyIndex = f;
                        captureKeyView = change;
                        statusView.setText("CHANGE " + LABEL[f]
                                + ": اضغط الزر المطلوب فاليد دابا…");
                    }
                });
                controlRow.addView(change, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
                changeButtons[i] = change;

                final Button clicks = mkButton(a,
                        f < 2 ? "STICK" : clickCount[f] + "×", 8f);
                clicks.setEnabled(slotEnabled[i] && f >= 2);
                clicks.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        clickCount[f] = clickCount[f] >= 5 ? 1 : clickCount[f] + 1;
                        clicks.setText(clickCount[f] + "×");
                        savePositions(a);
                        statusView.setText(LABEL[f] + " = " + clickCount[f]
                                + " clicks for every press");
                    }
                });
                controlRow.addView(clicks, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0.30f));
                clickButtons[i] = clicks;
                column.addView(controlRow);
            }

            Button addCustom = mkButton(a, "+ ADD ANY SCREEN BUTTON", 11f);
            addCustom.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int i = F_CUSTOM1; i < FN; i++) {
                        if (!slotEnabled[i]) {
                            slotEnabled[i] = true;
                            positionButtons[i].setEnabled(true);
                            changeButtons[i].setEnabled(true);
                            clickButtons[i].setEnabled(true);
                            savePositions(a);
                            toast(a, LABEL[i] + " added — set PLACE then CHANGE", Toast.LENGTH_LONG);
                            return;
                        }
                    }
                    toast(a, "5 custom buttons already added", Toast.LENGTH_SHORT);
                }
            });
            column.addView(addCustom);

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
                    final int f = lastCapture >= 0 ? lastCapture : F_X;
                    toast(a, "تجربة: " + LABEL[f], Toast.LENGTH_SHORT);
                    boolean wasCal = calibrating;
                    calibrating = false;          /* allow the test injection */
                    activateFunc(a, f, true);
                    handler().postDelayed(new Runnable() {
                        public void run() {
                            activateFunc(a, f, false);
                        }
                    }, 750L);
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
            int panelWidth = Math.min((int) dp(a, 430), (int) (viewW(a) * 0.78f));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    panelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
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
                lp.dimAmount = 0.0f;
                w.setAttributes(lp);
            }
            d.setOnKeyListener(new Dialog.OnKeyListener() {
                public boolean onKey(android.content.DialogInterface di, int code, KeyEvent e) {
                    if (captureKeyIndex >= 2 && e.getAction() == KeyEvent.ACTION_DOWN) {
                        assignCapturedKey(a, captureKeyIndex, code);
                        return true;
                    }
                    /* Phone BACK closes; controller SELECT is an ordinary,
                     * fully remappable button just like every other PS3 key. */
                    if (code == KeyEvent.KEYCODE_BACK && e.getDeviceId() < 0
                            && e.getAction() == KeyEvent.ACTION_UP) {
                        hideCalibration();
                        return true;
                    }
                    return calibrating;
                }
            });
            d.show();
            dialog = d;
            try {
                a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE).edit()
                        .putBoolean("seen_" + VER, true).commit();
            } catch (Throwable ignored) {
            }
            refreshChip(null);
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
        captureKeyIndex = -1;
        captureKeyView = null;
        statusView = null;
        placer = null;
        for (int i = 0; i < FN; i++) {
            marks[i] = null;
        }
        refreshChip(null);
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
            drawButtonPreview(a);
        }
        Log.i(TAG, "calibration closed");
    }
}
