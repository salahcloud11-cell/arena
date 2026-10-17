package com.ps3mod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
 * DualShock 3 -> Ninja Arashi, by replaying pad input as the game's own touch
 * controls (the "developer buttons": the same MotionEvents a finger produces on
 * the game's on-screen buttons).
 *
 * The game is touch-only: it has no gamepad support, so nothing can be handed to
 * it as a "real" button press.  What it does understand is touches, and those
 * are what this class produces -- up to 10 independent fingers, so actions can
 * be combined (run + jump + attack at once).
 *
 * Entry points (called from PS3Activity, on the UI thread):
 *   onKey(Activity, KeyEvent)     -- buttons, D-pad, SELECT
 *   onMotion(Activity, MotionEvent)-- sticks (joystick axes)
 *   attach / onActivityResumed    -- setup, pad detection, first-run calibration
 */
public class PS3Mapper {

    private static final String TAG = "PS3Mapper";

    /* ------------------------------------------------------------------ */
    /* functions                                                          */
    /* ------------------------------------------------------------------ */
    private static final int F_MOVE = 0, F_JUMP = 1, F_ATTACK = 2, F_DASH = 3,
            F_TRANS = 4, F_FI = 5, F_PAUSE = 6, F_ALT = 7;
    private static final int FN = 8;

    private static final String[] SKEY = {"move", "jump", "attack", "dash",
            "transform", "throw", "pause", "alt"};
    private static final String[] LABEL = {
            "حركة (مركز العصا/اليسار)", "قفز ✕", "هجوم □", "داش ○",
            "تحوّل L1", "رمي △", "إيقاف START", "يمين (وضع الأزرار فقط)"};

    /** First-run guesses; the calibration screen replaces them. */
    private static final float[] DX = {0.120f, 0.880f, 0.760f, 0.640f,
            0.520f, 0.880f, 0.950f, 0.760f};
    private static final float[] DY = {0.800f, 0.800f, 0.930f, 0.930f,
            0.930f, 0.600f, 0.070f, 0.700f};

    /**
     * hold = the finger stays down while the pad button is held (movement and
     * the two main actions), tap = a short press (dash, transform, throw, pause).
     */
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
    private static boolean enabled = true;
    private static boolean connectedToast = false;
    private static boolean overlay = false;
    private static View overlayView = null;
    private static boolean calibrating = false;
    private static int captureIndex = -1;
    private static boolean joyMode = true;

    private static boolean dLeft = false, dRight = false, dUp = false, dDown = false;
    private static float axisX = 0f;
    private static boolean leftHeld = false, rightHeld = false, joyHeld = false;

    private static long lastPadEvent = 0L;
    private static boolean noPadWarned = false;
    private static boolean resumedOnce = false;

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

    /** Called from PS3Activity.onResume: pad detection, first-run calibration. */
    public static void onActivityResumed(final Activity a) {
        if (a == null) {
            return;
        }
        attach(a);
        handler().postDelayed(new Runnable() {
            public void run() {
                reportPadStatus(a);
            }
        }, 1500L);
        if (!calibrated(a)) {
            handler().postDelayed(new Runnable() {
                public void run() {
                    showCalibration();
                }
            }, 3200L);
        }
        if (!resumedOnce) {
            resumedOnce = true;
            handler().postDelayed(new Runnable() {
                public void run() {
                    if (lastPadEvent == 0L && !noPadWarned) {
                        noPadWarned = true;
                        toast(a, "لا توجد إشارة من اليد بعد — وصّلها قبل فتح اللعبة");
                    }
                }
            }, 20000L);
        }
    }

    /**
     * Tells the player exactly what Android sees: it is the difference between
     * "the pad is not connected" and "the pad is connected but the game ignores
     * it", which cannot be guessed without a device.
     */
    private static void reportPadStatus(Activity a) {
        try {
            int[] ids = InputDevice.getDeviceIds();
            StringBuilder sb = new StringBuilder();
            int pads = 0;
            for (int i = 0; i < ids.length; i++) {
                InputDevice d = InputDevice.getDevice(ids[i]);
                if (d == null) {
                    continue;
                }
                int src = d.getSources();
                boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                if (pad) {
                    pads++;
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(d.getName());
                }
            }
            Log.i(TAG, "pad scan: " + pads + " gamepad(s) [" + sb + "]");
            if (pads == 0) {
                toast(a, "ما كايناش يد متصلة — وصّل الـ PS3 قبل ما تفتح اللعبة");
            } else {
                toast(a, "اليد متصلة: " + sb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pad scan failed", t);
        }
    }

    private static void toast(Activity a, String msg) {
        try {
            if (a != null) {
                Toast.makeText(a.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void padSeen(Activity a) {
        lastPadEvent = SystemClock.uptimeMillis();
        noPadWarned = false;
        if (!connectedToast) {
            connectedToast = true;
            toast(a, "PS3: اليد شغالة ✓ — زر SELECT للمعايرة");
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

    public static boolean isCalibrating() {
        return calibrating;
    }

    public static void showCalibrationPublic() {
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

    /**
     * Directional movement.  Two styles, because the game's left control is
     * either a drag joystick or a pair of arrow buttons:
     *   joyMode  -> one finger on the calibrated centre, slid left/right
     *   buttons  -> a held finger on the left or right button
     */
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
            ptrUp(a, F_ALT);              /* right button uses its own pointer */
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

    private static int functionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:      /* X / Cross  */
                return F_JUMP;
            case KeyEvent.KEYCODE_BUTTON_B:      /* O / Circle */
                return F_DASH;
            case KeyEvent.KEYCODE_BUTTON_X:      /* Square     */
                return F_ATTACK;
            case KeyEvent.KEYCODE_BUTTON_Y:      /* Triangle   */
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
    /** @return true when the mapper consumed the event. */
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

            /* SELECT on a DualShock 3 arrives as KEYCODE_BACK.  Only treat BACK
             * as SELECT when it comes from a gamepad, so the phone's own back
             * key keeps working. */
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!pad) {
                    return false;
                }
                padSeen(a);
                if (ev.getAction() == KeyEvent.ACTION_UP) {
                    if (calibrating) {
                        hideCalibration();
                    } else {
                        showCalibration();
                    }
                }
                return true;
            }

            if (!pad && !isDpad(code)) {
                return false;
            }
            if (ev.getRepeatCount() > 0) {
                return true;
            }
            padSeen(a);
            if (!enabled || calibrating) {
                return true;
            }

            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
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

    /** @return true when the mapper consumed the event. */
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
    /* calibration overlay                                                */
    /* ------------------------------------------------------------------ */
    private static float dp(Activity a, float v) {
        return v * a.getResources().getDisplayMetrics().density;
    }

    static void showCalibration() {
        final Activity a = activity;
        if (a == null || overlay) {
            return;
        }
        try {
            calibrating = true;
            releaseAll(a);

            final FrameLayout root = new FrameLayout(a);
            root.setBackgroundColor(0x66000000);

            final LinearLayout panel = new LinearLayout(a);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xDD000000);
            panel.setPadding((int) dp(a, 6), (int) dp(a, 6), (int) dp(a, 6), (int) dp(a, 6));
            panel.setClickable(true);   /* a tap on the panel must not set a position */

            TextView title = new TextView(a);
            title.setText("معايرة يد PS3\n١) اضغط زرً من القائمة  ٢) المس مكان زرّ اللعبة على الشاشة\nثم «حفظ وإغلاق». (زر SELECT أو Back يفتح/يسد هاد الشاشة)");
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
                        toast(a, "المس مكان: " + LABEL[f]);
                    }
                });
                panel.addView(b, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            final Button modeBtn = new Button(a);
            modeBtn.setTextSize(10f);
            modeBtn.setText(joyMode ? "الحركة: سحب عصا ✔ (بدّل لزر ثابت)" : "الحركة: زر ثابت (بدّل لعصا)");
            modeBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    joyMode = !joyMode;
                    modeBtn.setText(joyMode ? "الحركة: سحب عصا ✔ (بدّل لزر ثابت)" : "الحركة: زر ثابت (بدّل لعصا)");
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
                    hideCalibration();
                    toast(a, "تم حفظ المعايرة ✓");
                }
            });
            panel.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.TOP | Gravity.LEFT;
            plp.leftMargin = (int) dp(a, 10);
            plp.topMargin = (int) dp(a, 10);
            root.addView(panel, plp);

            root.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN && captureIndex >= 0) {
                        int w = root.getWidth() > 0 ? root.getWidth() : viewW(a);
                        int h = root.getHeight() > 0 ? root.getHeight() : viewH(a);
                        px[captureIndex] = e.getX() / w;
                        py[captureIndex] = e.getY() / h;
                        captureIndex = -1;
                        panel.setVisibility(View.VISIBLE);
                        return true;
                    }
                    return false;
                }
            });

            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            decor.addView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlayView = root;
            overlay = true;
            Log.i(TAG, "calibration opened");
        } catch (Throwable t) {
            overlay = false;
            calibrating = false;
            Log.w(TAG, "calibration failed", t);
        }
    }

    private static void hideCalibration() {
        final Activity a = activity;
        calibrating = false;
        captureIndex = -1;
        try {
            if (overlayView != null && overlayView.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlayView.getParent()).removeView(overlayView);
            }
        } catch (Throwable ignored) {
        }
        overlayView = null;
        overlay = false;
        if (a != null) {
            releaseAll(a);
        }
        Log.i(TAG, "calibration closed");
    }
}
