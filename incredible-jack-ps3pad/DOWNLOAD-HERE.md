# Incredible Jack 1.36.6 + PS3 Smart Controller v2

## Download

**https://drive.google.com/file/d/1NGco3pUTxyDr3b2_QGK1iPN_tRh4_UCM/view**

- Size: 88,671,546 bytes
- SHA-256: `1252c7650ccfde4fa1fbec8fecf680f9dfb2c502ff801b545115e5dab7d2cc25`
- Package: `com.chillingo.incrediblejack.android.rowgplay1`
- Version: `1.36.6-ps3v2`
- Icon: **Incredible Jack PS3v2**
- ABIs: `armeabi-v7a`, `arm64-v8a`

The mod uses a different signature. Uninstall the original or v1 before installing v2. Back up local
save data first when possible.

## Hide the complete small menu bar

Press **HIDE BAR** in the small menu or **HIDE SMALL MENU BAR** in Control Studio. The entire bar
becomes `GONE`, not transparent, and the choice is saved.

Press **L3 + R3 together** at any time to show or hide it again. This recovery shortcut works even
when the bar and all touch targets are invisible.

## Right-stick mouse

- Move the cursor with the **right analog stick**.
- Press **R3** to click at the cursor position.
- Toggle `RIGHT STICK MOUSE` in the bar or Control Studio.
- Cursor position and mouse mode are saved.
- Smart axis detection supports both modern `RX/RY` and classic DS3 `Z/RZ` right-stick layouts.

## Smart PlayStation Input

Smart Input is enabled by default and can be toggled in Control Studio. It handles:

- Standard Android A/B/X/Y, L1/R1/L2/R2, START/SELECT and L3/R3 events.
- Raw PlayStation-driver `BUTTON_1` through `BUTTON_12` layouts.
- D-pad key events and `HAT_X/HAT_Y` axis-only D-pads.
- Direct activity events plus the independent InputManager reader, with exact-transition
  de-duplication so releases are not lost.
- Trigger range detection and hysteresis to prevent analog L2/R2 chatter.
- Right-stick layout detection per connected device.

All controls still use invisible synthetic touches on the game's own buttons. Every target remains
placeable/remappable and supports 1x–5x multi-click. Five extra targets are available.
