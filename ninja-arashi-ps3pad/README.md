# Ninja Arashi 1.4 + PS3 controller (DualShock 3)

Native DualShock 3 / Sixaxis support for **Ninja Arashi 1.4**
(`com.blackpanther.ninjaarashi`, Unity 2018.3 IL2CPP), added without touching a
single game asset: a small injected library drives the game's own control
methods, so the ninja reacts to the pad exactly as it does to the on-screen
buttons.

* **[Download the patched APK →](../../releases/latest)** (arm64-v8a, signed, ready to install)
* **[ps3pad.ini](ps3pad.ini)** – button remapping / dead zone config
* Sources: [`src/`](src) · build tools: [`tools/`](tools) · metadata evidence: [`analysis/`](analysis)

---

## Install

The APK is signed with a new key, so Android will not install it over the copy
you already have.

1. Uninstall the existing Ninja Arashi (this deletes its saved progress).
2. Install the patched APK:
   ```
   adb uninstall com.blackpanther.ninjaarashi     # if still installed
   adb install ninja-arashi-1-4-ps3pad.apk
   ```
   On the phone itself: allow "install unknown apps" for your file manager and
   tap the APK.
3. Do not update it from Google Play afterwards — Play would replace it and the
   controller support would be gone.

You need the original, paid-for/free-to-play game for Android; this repository
only carries what is required to *modify* that APK, not the game itself.

## Connect the DualShock 3

* **USB OTG cable (recommended, no root):** plug the controller in. Android
  detects it as a gamepad immediately and the mod picks it up.
* **Bluetooth:** the DS3 uses Sony's own pairing handshake, which stock Android
  does not implement, so on most phones a helper app or root is needed. If it is
  already paired it works the same way.
* On an Android TV box the pad is often joystick 1 or 2 — both are covered.

## Controls

| DualShock 3 | Action | Game entry point |
|---|---|---|
| Left stick ←/→ or D-pad ←/→ | run left / right | `Button_Left/Right_press/release` |
| ✕ Cross | jump | `Button_Jump_press/release` |
| □ Square | attack / check | `Button_Check_press/release` |
| ○ Circle | dash | `Button_Dash_press` |
| △ Triangle | throw (Fi) | `Button_Fi_press` |
| L1 / R1 | transform | `Button_Trans_press` |

Menus (play / pause / restart) are still touch driven — the mod only drives the
character's own control methods. Cross/Circle also register as the InputManager's
"Submit"/"Cancel", so if the game's UI listens to Unity's EventSystem the pad may
navigate menus as well.

## How it works

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
