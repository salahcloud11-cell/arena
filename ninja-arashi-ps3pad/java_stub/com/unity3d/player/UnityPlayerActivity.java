package com.unity3d.player;

/**
 * Compile-time stand-in for Unity's player activity.
 *
 * PS3Activity extends this class so the overridden dispatch methods run before
 * the engine sees controller input.  The real implementation ships with the
 * game (it is in the APK's dex), so this stub is only used by javac/d8: when the
 * mapper's smali is merged into the game's tree, the game's own
 * com/unity3d/player/UnityPlayerActivity.smali is kept and this one is discarded.
 *
 * It intentionally declares no members: the methods PS3Activity overrides
 * (dispatchKeyEvent, dispatchGenericMotionEvent, dispatchTouchEvent, onCreate,
 * onResume) all exist on android.app.Activity, so the super calls bind the same
 * way whether the real class overrides them or not.
 */
public class UnityPlayerActivity extends android.app.Activity {
}
