# Universal PS3 Mapper

A separate Android 7+ (`minSdk 24`) accessibility mapper. It does not modify games.

## Download

**[Download Universal-PS3-Mapper-v2.apk from Google Drive](https://drive.google.com/file/d/1fDzNNzPJHNFlycd0qrb-FmVXPMj62kLZ/view)**

- Size: **25,078 bytes**
- SHA-256: `285e97e49cb3b3fcf044bd563ca076c9d0a8accd86a0842940fee301014f7015`
- Package: `com.ps3mod.universal`
- Version: `2.0`

## No-root mode

1. Install and open **Universal PS3 Mapper**.
2. Enable its accessibility service.
3. Open a game and tap the green **MAPPER / EDIT** bar.
4. Add a target, drag it over a game control, tap it, and press the PS3 button to assign.
5. Long-press a target to cycle 1–5 clicks. Profiles save per game package.
6. **TARGETS OFF** makes editor markers faint; **SAVE / PLAY** removes all target overlays.
7. L3+R3 always restores a hidden Mapper bar.

Android accessibility services receive global hardware *keys*, but Android does not expose global
joystick `MotionEvent` axes to ordinary background apps. Button and D-pad mappings therefore work
without root; analog cursor input uses the optional bridge below.

## Enhanced ADB analog mode

Enable USB debugging, enable the mapper service, connect the phone, then run:

```bash
python3 tools/ps3-adb-bridge.py
```

The helper identifies the Linux gamepad event device, forwards normalized right-stick and button
events over an ADB-local socket, and displays an edge-bounded right-stick cursor. CROSS clicks the
cursor. Use `--device /dev/input/eventN` if automatic detection selects the wrong device.

This mode needs ADB to remain connected while playing, but it does not require root. The protocol is
also suitable for a future Shizuku process (`AXIS x y` and `KEY androidKeyCode 0|1` on local TCP 37654).

## Security

The bridge server binds only to `127.0.0.1`. Profiles remain in private application preferences.
The service observes active package changes and controller key events; it does not upload content.
