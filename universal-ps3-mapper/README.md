# Universal PS3 Mapper v3

A separate Android 7+ (`minSdk 24`) accessibility mapper. It does not modify games.

## Download

**[Download Universal-PS3-Mapper-v3.apk](https://drive.google.com/file/d/1xb1iNPLgKk8smCvrDS9R61v2wtSGZ7zH/view)**

- Size: **25,078 bytes**
- SHA-256: `7bf71c46b158810d88f04eeae6c24317d798a3c5bd088a26ac199a1a5b20fe44`
- Package: `com.ps3mod.universal`
- Version: `3.0`

## Customization menu

v3 replaces the unclear gesture-only editor with an Incredible-Jack-style explicit control menu.

1. Install the APK and enable **Universal PS3 Mapper** in Accessibility settings.
2. Open the game, then tap **EDIT** on the green Mapper bar.
3. Press **+ TARGET** and tap the exact game-button location.
4. Use the visible menu beside every target:
   - **PLACE** — tap a new position.
   - **CHANGE** — press the PS3 button you want to assign.
   - **×1…×5** — choose the number of taps from one controller press.
   - **DEL** — remove the target.
5. Press **SAVE / PLAY**. Target overlays disappear during play.

Button assignment now uses two readers: Accessibility key events and the same hidden InputManager
polling method proven in Incredible Jack. This fixes controllers whose buttons were visible to Android
but could not be captured while the editor overlay was open.

The **MOUSE** switch enables a no-root D-pad cursor; CROSS clicks it. L3+R3 always restores a hidden
Mapper bar. Profiles remain separate for each game package.

## Enhanced ADB analog mode

Android does not expose global analog joystick MotionEvents to ordinary background applications.
For a right-stick cursor, enable USB debugging and run while connected:

```bash
python3 tools/ps3-adb-bridge.py
```

The helper detects the gamepad event device, normalizes its right stick and forwards it to the mapper.
Use `--device /dev/input/eventN` if automatic detection selects the wrong device.

## Security

The optional bridge binds only to `127.0.0.1`. Profiles remain in private application preferences.
The service does not upload game content.
