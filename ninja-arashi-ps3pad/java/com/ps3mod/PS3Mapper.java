package com.ps3mod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * PS3 (DualShock 3) mapper for Ninja Arashi -- turns gamepad input into
 * synthetic touchscreen events, the same way the working Granny/CoD mods do.
 *
 * Ninja Arashi is a touch-only Unity game, so nothing here depends on Unity's
 * (absent) gamepad support and nothing depends on the CPU architecture: this is
 * plain Java, so it works on 32-bit and 64-bit devices alike.
 *
 * Pad -> game mapping (all positions are fractions of the screen and can be
 * re-calibrated on the device itself -- see the calibration overlay):
 *
 *   left stick / D-pad left-right ... run left / right   (hold)
 *   CROSS / R1 / D-pad up ............ jump              (hold)
 *   SQUARE / R1 ...................... attack / check    (hold)
 *   CIRCLE / R2 ...................... dash              (tap)
 *   TRIANGLE ......................... throw (Fi)        (tap)
 *   L1 / L2 .......................... transform         (tap)
 *   START ............................ pause menu        (tap)
 *   SELECT (Back button) ............. open calibration  (consumed, never quits)
 *
 * Everything runs on the UI thread: the entry points are called from the
 * window-callback proxy installed by {@link PS3App}, so synthetic touches can
 * be dispatched straight away, exactly like a finger.
 */
public class PS3Mapper {

    /* ------------------------------------------------------------------ */
    /* functions                                                          */
    /* ------------------------------------------------------------------ */
    private static final int F_LEFT = 0, F_RIGHT = 1, F_JUMP = 2, F_ATTACK = 3,
            F_DASH = 4, F_TRANS = 5, F_FI = 6, F_PAUSE = 7;
    private static final int FN = 8;

    private static final String[] SKEY = {"left", "right", "jump", "attack",
            "dash", "transform", "throw", "pause"};
    private static final String[] LABEL = {"يسار (حركة)", "يمين (حركة)", "قفز",
            "هجوم (سيف)", "داش (انطلاق)", "تحوّل", "رمي / Fi", "إيقاف مؤقت"};

    /** First-run defaults; overwritten by whatever you calibrate. */
    private static final float[] DX = {0.070f, 0.180f, 0.900f, 0.780f,
            0.660f, 0.545f, 0.430f, 0.945f};
    private static final float[] DY = {0.800f, 0.800f, 0.780f, 0.880f,
            0.880f, 0.880f, 0.880f, 0.070f};

    /** hold = keep the finger down while the button is held. */
    private static final boolean[] HOLD = {true, true, true, true,
            false, false, false, false};

    private static final float[] px = new float[FN];
    private static final float[] py = new float[FN];

    /* ------------------------------------------------------------------ */
    /* synthetic multi-touch engine (max 10 pointers)                     */
    /* ------------------------------------------------------------------ */
    private static final int MAX_POINTERS = 10;
    private static final boolean[] pActive = new boolean[MAX_POINTERS];
    private static final float[] pX = new float[MAX_POINTERS];
    private static final float[] pY = new float[MAX_POINTERS];
    private static int pCount = 0;
    private static long gestureDownTime = 0L;

    private static final long TAP_RELEASE_MS = 90L;

    /** Movement style: true = drag the movement control (most games use a
     *  joystick/drag zone), false = just hold the button down.  Toggleable in
     *  the calibration screen, and remembered. */
    private static boolean joyMode = true;
    private static final float JOY_RADIUS = 0.06f;
    private static final float STICK_DEADZONE = 0.28f;

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
    private static boolean enabled = true;
    private static boolean toastShown = false;
    private static boolean attached = false;
    private static View overlay = null;
    private static boolean calibrating = false;
    private static int captureIndex = -1;

    private static boolean dLeft = false, dRight = false, dUp = false, dDown = false;
    private static float axisX = 0f;
    private static boolean moveLeftHeld = false, moveRightHeld = false;

    private static int lastDeviceId = -1;

    /* ------------------------------------------------------------------ */
    /* setup                                                              */
    /* ------------------------------------------------------------------ */
    /** Called once per activity from PS3App (UI thread). */
    public static void attach(Activity a) {
        if (a == null) {
            return;
        }
        activity = a;
        loadPositions(a);
        if (attached) {
            return;
        }
        attached = true;
        attachViewListeners(a);
    }

    /** Fallback path: listeners on the view tree, in case the window callback
     *  proxy is not installed (harmless duplicate if both work). */
    private static void attachViewListeners(final Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                return;
            }
            walk((ViewGroup) decor, a);
        } catch (Throwable ignored) {
        }
    }

    private static void walk(ViewGroup g, final Activity a) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            try {
                c.setOnKeyListener(new View.OnKeyListener() {
                    public boolean onKey(View v, int code, KeyEvent e) {
                        /* qualify: OnKeyListener.onKey would otherwise win */
                        return PS3Mapper.onKey(a, e);
                    }
                });
                c.setOnGenericMotionListener(new View.OnGenericMotionListener() {
                    public boolean onGenericMotion(View v, MotionEvent e) {
                        return PS3Mapper.onMotion(a, e);
                    }
                });
            } catch (Throwable ignored) {
            }
            if (c instanceof ViewGroup) {
                walk((ViewGroup) c, a);
            }
        }
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
            e.commit();
        } catch (Throwable ignored) {
        }
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
        } catch (Throwable ignored) {
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

    /** Reposition a pointer that is already down (a real finger drag). */
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
        moveLeftHeld = false;
        moveRightHeld = false;
    }

    private static float fx(Activity a, int f) {
        return px[f] * viewW(a);
    }

    private static float fy(Activity a, int f) {
        return py[f] * viewH(a);
    }

    /** Press a function's spot (hold-style: stays down until releaseFunc). */
    private static void pressFunc(Activity a, int f) {
        if (calibrating) {
            return;
        }
        ptrDown(a, f, fx(a, f), fy(a, f));
    }

    private static void releaseFunc(Activity a, int f) {
        ptrUp(a, f);
    }

    /** Quick tap (press + release after TAP_RELEASE_MS). */
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

    private static int tapGen = 0;

    private static void releaseTaps() {
        tapGen++;
    }

    /* ------------------------------------------------------------------ */
    /* movement                                                           */
    /* ------------------------------------------------------------------ */
    private static float deadzone(float v) {
        float a = Math.abs(v);
        if (a < STICK_DEADZONE) {
            return 0f;
        }
        float s = (a - STICK_DEADZONE) / (1f - STICK_DEADZONE);
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
        boolean wantLeft = x < -0.05f;
        boolean wantRight = x > 0.05f;
        if (wantLeft && moveRightHeld) {
            releaseFunc(a, F_RIGHT);
            moveRightHeld = false;
        }
        if (wantRight && moveLeftHeld) {
            releaseFunc(a, F_LEFT);
            moveLeftHeld = false;
        }
        if (wantLeft && !moveLeftHeld) {
            pressFunc(a, F_LEFT);
            moveLeftHeld = true;
        }
        if (wantRight && !moveRightHeld) {
            pressFunc(a, F_RIGHT);
            moveRightHeld = true;
        }
        if (!wantLeft && moveLeftHeld) {
            releaseFunc(a, F_LEFT);
            moveLeftHeld = false;
        }
        if (!wantRight && moveRightHeld) {
            releaseFunc(a, F_RIGHT);
            moveRightHeld = false;
        }
        if (joyMode) {
            /* Movement in most of these games is a drag zone rather than two
             * buttons, so slide the finger from the calibrated spot by an
             * amount proportional to the stick.  The offset is small enough
             * that it also stays inside a normal on-screen button. */
            float mag = Math.abs(x);
            if (mag > 1f) {
                mag = 1f;
            }
            if (wantRight && moveRightHeld) {
                ptrMove(a, F_RIGHT, fx(a, F_RIGHT) + mag * JOY_RADIUS * viewW(a), fy(a, F_RIGHT));
            } else if (wantLeft && moveLeftHeld) {
                ptrMove(a, F_LEFT, fx(a, F_LEFT) - mag * JOY_RADIUS * viewW(a), fy(a, F_LEFT));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* pad events                                                         */
    /* ------------------------------------------------------------------ */
    private static void toastOnce(Activity a) {
        if (toastShown) {
            return;
        }
        toastShown = true;
        try {
            Toast.makeText(a.getApplicationContext(),
                    "PS3 controller connected - Ninja Arashi ready",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        if (!calibrated(a)) {
            handler().postDelayed(new Runnable() {
                public void run() {
                    showCalibration();
                }
            }, 2500L);
        }
    }

    private static boolean calibrated(Activity a) {
        try {
            return a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE).getBoolean("calibrated", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int functionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:      /* X / Cross  (bottom) */
                return F_JUMP;
            case KeyEvent.KEYCODE_BUTTON_B:      /* O / Circle (right)  */
                return F_DASH;
            case KeyEvent.KEYCODE_BUTTON_X:      /* Square     (left)   */
                return F_ATTACK;
            case KeyEvent.KEYCODE_BUTTON_Y:      /* Triangle   (top)    */
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

    /** @return true when the event was consumed by the mapper. */
    public static boolean onKey(Activity a, KeyEvent ev) {
        if (a == null || ev == null) {
            return false;
        }
        try {
            if (activity == null) {
                attach(a);
            }
            int code = ev.getKeyCode();
            int src = ev.getSource();
            boolean fromPad = (src & InputDevice.SOURCE_GAMEPAD) != 0
                    || (src & InputDevice.SOURCE_JOYSTICK) != 0;
            if (code == KeyEvent.KEYCODE_BACK && !calibrating && !fromPad) {
                return false;                     /* real soft-key back: leave it */
            }
            if (code == KeyEvent.KEYCODE_BACK) {  /* SELECT: calibration screen */
                if (ev.getAction() == KeyEvent.ACTION_UP) {
                    if (calibrating) {
                        hideCalibration();
                    } else {
                        showCalibration();
                    }
                }
                return true;
            }
            if (!fromPad && code != KeyEvent.KEYCODE_DPAD_UP
                    && code != KeyEvent.KEYCODE_DPAD_DOWN
                    && code != KeyEvent.KEYCODE_DPAD_LEFT
                    && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;                     /* keyboard etc.: not ours */
            }
            if (ev.getRepeatCount() > 0) {
                return true;                      /* already handled */
            }
            lastDeviceId = ev.getDeviceId();
            toastOnce(a);
            if (!enabled || calibrating) {
                return true;
            }
            int action = ev.getAction();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                boolean down = action == KeyEvent.ACTION_DOWN;
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
            if (action == KeyEvent.ACTION_DOWN) {
                if (HOLD[f]) {
                    pressFunc(a, f);
                } else {
                    tapFunc(a, f);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (HOLD[f]) {
                    releaseFunc(a, f);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return true when the event was consumed by the mapper. */
    public static boolean onMotion(Activity a, MotionEvent ev) {
        if (a == null || ev == null) {
            return false;
        }
        try {
            if (activity == null) {
                attach(a);
            }
            int src = ev.getSource();
            boolean pad = (src & InputDevice.SOURCE_JOYSTICK) != 0
                    || (src & InputDevice.SOURCE_GAMEPAD) != 0;
            if (!pad) {
                return false;
            }
            if (ev.getAction() != MotionEvent.ACTION_MOVE) {
                return true;                      /* consume, nothing to do */
            }
            toastOnce(a);
            if (!enabled || calibrating) {
                return true;
            }
            lastDeviceId = ev.getDeviceId();
            float x = ev.getAxisValue(MotionEvent.AXIS_X) + ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            axisX = deadzone(x > 1f ? 1f : (x < -1f ? -1f : x));
            updateMove(a);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* calibration overlay                                                */
    /* ------------------------------------------------------------------ */
    private static float dp(Activity a, float v) {
        return v * a.getResources().getDisplayMetrics().density;
    }

    private static void showCalibration() {
        final Activity a = activity;
        if (a == null || overlay != null) {
            return;
        }
        try {
            calibrating = true;
            releaseAll(a);
            releaseTaps();

            final FrameLayout root = new FrameLayout(a);
            root.setBackgroundColor(0x66000000);

            final LinearLayout panel = new LinearLayout(a);
            panel.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(a);
            title.setText("معايرة يد PS3\nاضغط على أي زر ثم المس مكان زرّ اللعبة على الشاشة.\nإلا ما خدمتش الحركة، بدّل (طريقة الحركة) لـ زر ثابت.\nثم اضغط حفظ. (زر Back = إغلاق)");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(11f);
            panel.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < FN; i++) {
                final int f = i;
                Button b = new Button(a);
                b.setText(LABEL[i]);
                b.setTextSize(10f);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        captureIndex = f;
                        ((ViewGroup) v.getParent()).setVisibility(View.GONE);
                    }
                });
                panel.addView(b, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final Button modeBtn = new Button(a);
            modeBtn.setTextSize(10f);
            modeBtn.setText(joyMode ? "طريقة الحركة: سحب (عصا) ✔" : "طريقة الحركة: زر ثابت");
            modeBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    joyMode = !joyMode;
                    modeBtn.setText(joyMode ? "طريقة الحركة: سحب (عصا) ✔" : "طريقة الحركة: زر ثابت");
                }
            });
            panel.addView(modeBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button save = new Button(a);
            save.setText("حفظ وإغلاق");
            save.setTextSize(11f);
            save.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    savePositions(a);
                    try {
                        a.getSharedPreferences("ps3pad", Context.MODE_PRIVATE)
                                .edit().putBoolean("calibrated", true).commit();
                    } catch (Throwable ignored) {
                    }
                    hideCalibration();
                    try {
                        Toast.makeText(a.getApplicationContext(),
                                "تم حفظ المعايرة", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
            panel.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.setBackgroundColor(0xBB000000);
            panel.setPadding((int) dp(a, 6), (int) dp(a, 6), (int) dp(a, 6), (int) dp(a, 6));

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.TOP | Gravity.LEFT;
            plp.leftMargin = (int) dp(a, 8);
            plp.topMargin = (int) dp(a, 8);
            root.addView(panel, plp);

            final TextView hint = new TextView(a);
            hint.setText("اختر زرً من القائمة… أو المس الشاشة لتسجيل الموضع الحالي");
            hint.setTextColor(0xFFFFFFFF);
            hint.setTextSize(10f);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            hlp.leftMargin = (int) dp(a, 8);
            hlp.bottomMargin = (int) dp(a, 8);
            root.addView(hint, hlp);

            root.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN && captureIndex >= 0) {
                        int w = root.getWidth() > 0 ? root.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
                        int h = root.getHeight() > 0 ? root.getHeight() : a.getResources().getDisplayMetrics().heightPixels;
                        px[captureIndex] = e.getX() / w;
                        py[captureIndex] = e.getY() / h;
                        final int done = captureIndex;
                        captureIndex = -1;
                        panel.setVisibility(View.VISIBLE);
                        try {
                            Toast.makeText(a.getApplicationContext(),
                                    "تم تسجيل: " + LABEL[done],
                                    Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    return false;
                }
            });

            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            decor.addView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = root;
        } catch (Throwable t) {
            overlay = null;
            calibrating = false;
        }
    }

    private static void hideCalibration() {
        final Activity a = activity;
        calibrating = false;
        captureIndex = -1;
        try {
            if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        } catch (Throwable ignored) {
        }
        overlay = null;
        if (a != null) {
            releaseAll(a);
            releaseTaps();
        }
    }

    /* ------------------------------------------------------------------ */
    /* entry point for PS3App                                             */
    /* ------------------------------------------------------------------ */
    public static void start(android.app.Application app) {
        /* nothing to spawn: all work happens on pad events */
    }
}
