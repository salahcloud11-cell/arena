# Ninja Arashi 1.4 + PS3 controller (DualShock 3)

Full DualShock 3 support for **Ninja Arashi 1.4** (`com.blackpanther.ninjaarashi`,
Unity 2018.3 IL2CPP), for **32-bit and 64-bit Android devices** — the pad is read
from Android and replayed as synthetic touches, so nothing depends on the CPU
architecture (the first attempt in this folder used an arm64-only native hook and
did not load on 32-bit phones; that code stays in `src/` for reference only).

* **[Download the APK →](../../releases/latest)** (release `ninja-arashi-ps3-latest`)
* **[PS3-CONTROLLER-GUIDE.md](PS3-CONTROLLER-GUIDE.md)** – Arabic guide, controls, calibration
* Java sources: [`java/com/ps3mod/`](java/com/ps3mod) · build tool: [`tools/patch_apk.py`](tools/patch_apk.py)
* CI: [`.github/workflows/ninja-arashi-ps3.yml`](../.github/workflows/ninja-arashi-ps3.yml)

## Install

The APK is signed with a new key, so Android will not install it over the copy
you already have.

1. Uninstall the existing Ninja Arashi (this deletes its saved progress).
2. Install the release APK (`adb install ...` or tap it on the phone).
3. Connect the pad **before** starting the game (USB OTG is the root-free way).
4. Press **SELECT** (the pad's Back button) inside the game to open the
   calibration screen and drop each control where the game's own touch button is.
5. Do not update it from Google Play afterwards — Play would replace it.

## Controls

| DualShock 3 | Action |
|---|---|
| Left stick ←/→ or D-pad ←/→ | run left / right (held) |
| ✕ Cross (or D-pad up) | jump (held) |
| □ Square | attack / check (held) |
| ○ Circle / R2 | dash (tap) |
| △ Triangle | throw / Fi (tap) |
| L1 / L2 | transform (tap) |
| START | pause menu (tap) |
| SELECT | open the calibration screen (never quits the game) |

## How it works (Java mapper - the shipped path)

1. `com.ps3mod.PS3App` is registered as the app's `Application` class
   (`android:name` in the manifest). It installs a **dynamic proxy** over the
   activity's `Window.Callback`, so controller key/`genericMotion` events are
   seen *before* Unity's player activity swallows them.
   A `Proxy` is used instead of a hand-written `Window.Callback` implementation
   so the code needs only `java.lang.reflect` and cannot break when Android adds
   methods to that interface.
2. `com.ps3mod.PS3Mapper` converts the pad state into a real multi-touch stream
   (up to 10 pointers) and dispatches it with `Activity.dispatchTouchEvent`,
   exactly like a finger on the glass.
3. Both classes are compiled with `javac`/`d8` and merged into the game's own
   `classes.dex` (dex → smali via apktool, then rebuilt), so the APK stays
   single-dex and keeps working on Android < 5 too.
4. The calibration screen stores per-control screen positions in
   `SharedPreferences`; the defaults are only a starting guess.

## Build it yourself

CI runs the whole thing (see the workflow). Locally, with a JDK and the Android
build-tools:

```bash
python3 tools/patch_apk.py \
  --apk-in ninja-arashi-1-4.apk \
  --java-src java \
  --apktool apktool.jar \
  --android-jar "$ANDROID_HOME/platforms/android-34/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/34.0.0" \
  --java "$JAVA_HOME/bin/java" \
  --work build/work --keydir build/keys \
  --out build/ninja-arashi-1-4-ps3pad.apk
```

The script verifies the result: `apksigner verify` (v1 + v2), zip integrity, dex
method counts, package/launchable-activity badging, and that every game asset
(`global-metadata.dat`, `libunity.so`, `libil2cpp.so` for both ABIs) is
byte-for-byte identical to the original.



1. `lib/arm64-v8a/libps3pad.so` — a new native library added to the APK. It is
   built freestanding (`-nostdlib`): nothing is linked, all libc symbols are
   resolved by the Android linker from bionic at load time, and no anonymous
   executable memory is used (Android 10+ blocks that for apps targeting API
   29+, so the hook trampolines live in the library's own file-backed `PT_LOAD`).
2. `lib/arm64-v8a/libmain.so` — Unity's bootstrap library, which Java loads
   first, is patched to list `libps3pad.so` as a `DT_NEEDED` dependency so
   Android loads it into the game process automatically. Nothing else in that
   file changes (see [`tools/patch_libmain.py`](tools/patch_libmain.py)).
3. Its ELF constructor starts a thread that waits for `libil2cpp.so`, binds the
   IL2CPP C API, finds `Assembly-CSharp.dll` → `NinjaMovementScript`, and
   installs a hook on `NinjaMovementScript.Update`, which hands it the live
   character object on the Unity main thread once per frame.
4. Each frame it reads the pad through Unity's legacy Input API — the shipped
   InputManager already maps `Horizontal`/`Vertical` to joystick axes 0/1 and
   `Fire1..3`/`Jump` to joystick buttons 0..3 — and replays the state through the
   game's own `Button_*` handlers, i.e. the same code path the touch buttons use.
5. If anything does not add up at runtime (hook target not relocatable, method
   pointer not inside `libil2cpp.so`, library missing) it logs why and leaves the
   game untouched instead of patching something wrong.

## Log and troubleshooting

Logcat tag **`ps3pad`**:
```
adb logcat -s ps3pad
```
and the first writable of
`/sdcard/Android/data/com.blackpanther.ninjaarashi/files/ps3pad.log`,
`/sdcard/ps3pad.log`, `/data/local/tmp/ps3pad.log`.

A healthy start:
```
ps3pad: library loaded (constructor)
watcher: il2cpp C API bound
watcher: Assembly-CSharp.dll loaded
game: move=1 jump=1 attack=1 dash=1 special=1 transform=1
hook: patched Update at 0x...
ps3pad ACTIVE - DualShock 3 ready (hook installed)
```

| Symptom | Meaning / fix |
|---|---|
| `hook failed - game left untouched` | the first instructions of `Update` could not be relocated on this build; the game still runs, just without pad support. |
| `watcher: NinjaMovementScript.Update not found` | a different game build than 1.4. |
| Nothing in the log | the library was not loaded — check that the patched APK (not the original) is installed. |
| Stick works, buttons do not | different button numbering: set `log_buttons=1` in `ps3pad.ini`, press ✕/□ and read the `pad: press btn N` lines. |
| Ninja walks by itself | stick drift: raise `deadzone` (e.g. 500). |
| Barely moves at full stick | lower `deadzone` (e.g. 250). |
| Buttons do the wrong thing | remap them in `ps3pad.ini` using the numbers from the log. |

## Build it yourself

Requirements: Python 3 with `pip install pyelftools cryptography ziglang`, and
`openssl` (optional, for the final signature check).

```bash
# 1. build libps3pad.so (no Android NDK needed)
python3 tools/build_lib.py

# 2. patch the game's libmain.so, insert both files, re-sign, verify
python3 tools/build_apk.py --apk-in /path/to/ninja-arashi-1-4.apk \
                           --out build/ninja-arashi-1-4-ps3pad.apk
```

Step 2 reproduces the released APK byte-for-byte from the original game APK: the
library build is reproducible, the injected `libmain.so` change is deterministic,
and the ZIP entries keep their original order and timestamps.

### Verified on the released APK

* ZIP integrity, `resources.arsc` still uncompressed, all 428 entries present,
  every other entry (including the armeabi-v7a libraries) bit-identical to the
  original;
* the patched `libmain.so` parses as a sane ELF with seven `DT_NEEDED` entries,
  all of them 4 KB-mapped and correctly relocated;
* `libps3pad.so` is a valid stripped aarch64 shared object whose only undefined
  symbols are bionic ones;
* `openssl cms -verify` accepts `META-INF/CERT.RSA` over `META-INF/CERT.SF`, and
  every manifest/SF digest was recomputed and matched;
* the control API was read out of the game's own IL2CPP metadata — see
  [`analysis/control-api.txt`](analysis/control-api.txt).

Not verified (no phone or pad in the build environment): the live hook and the
per-button numbering of a specific pad. Both failure modes are non-destructive —
the game keeps working and the log says what happened.

## Limits

* **arm64 only.** The armeabi-v7a libraries are untouched, so a 32-bit phone
  installs and runs the *unmodified* game (no pad support, no crash). Run
  `python3 tools/build_lib.py --v7a` and add `lib/armeabi-v7a/libps3pad.so` if you
  want 32-bit support.
* Menus are not driven by the mod.
* The APK is signed with a new key: Play Games sign-in / leaderboards and any
  other signature-checked online feature may not work; ads and IAP are untouched.
* Ninja Arashi is copyright Black Panther Games. This is a personal-use mod of an
  APK you own — no game assets are redistributed here, only the patch.
