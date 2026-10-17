package com.ps3mod;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * The game's activity, subclassed so controller events are seen first.
 *
 * Why a subclass: Unity's UnityPlayerActivity overrides dispatchKeyEvent and
 * dispatchGenericMotionEvent and feeds them straight into the engine.  Those
 * overrides do not call Window.superDispatchKeyEvent, so a Window.Callback
 * wrapper (or any View listener) never sees pad input at all -- which is why the
 * SELECT button did nothing in the previous build.  Overriding the two dispatch
 * methods here runs *before* the engine sees the event.
 *
 * The class name is written into AndroidManifest.xml in place of
 * com.unity3d.player.UnityPlayerActivity, so the system instantiates this class
 * as the game's activity.
 */
public class PS3Activity extends com.unity3d.player.UnityPlayerActivity {

    private static final int CORNER_TAPS = 3;
    private static final long CORNER_TAP_WINDOW = 2500L;
    private static final float CORNER_FRACTION = 0.14f;

    private final long[] tapTimes = new long[CORNER_TAPS];
    private int tapCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            PS3Mapper.attach(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            PS3Mapper.attach(this);
            PS3Mapper.onActivityResumed(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        try {
            if (PS3Mapper.onKey(this, event)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        try {
            if (PS3Mapper.onMotion(this, event)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * Touch fallback for opening the calibration screen: three quick taps in the
     * top-left corner.  Needs no controller at all, so calibration stays
     * reachable even if the pad is not detected.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        try {
            if (cornerTapsOpenCalibration(event)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean cornerTapsOpenCalibration(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        if (PS3Mapper.isCalibrating()) {
            return false;
        }
        float w = getWindow() != null && getWindow().getDecorView().getWidth() > 0
                ? getWindow().getDecorView().getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        float h = getWindow().getDecorView().getHeight() > 0
                ? getWindow().getDecorView().getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        if (event.getX() > w * CORNER_FRACTION || event.getY() > h * CORNER_FRACTION) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (tapCount > 0 && now - tapTimes[(tapCount - 1) % CORNER_TAPS] > CORNER_TAP_WINDOW) {
            tapCount = 0;
        }
        tapTimes[tapCount % CORNER_TAPS] = now;
        tapCount++;
        if (tapCount >= CORNER_TAPS) {
            tapCount = 0;
            PS3Mapper.showCalibrationPublic();
            return true;
        }
        return false;
    }
}
