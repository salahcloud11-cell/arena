# COD:BOZ — Customizable PS3 Controls v2

## Download

**[Download COD-BOZ-PS3-Custom-v2.apk](https://drive.google.com/file/d/1WYbDAsNqahqfDNA_hH3w6rSdCCz3UU2k/view)**

- Package: `com.activision.boz`
- Launcher: `com.activision.boz.Main`
- Version: `1.0.8.1kotlin-ps3v2`
- Size: **43,928,812 bytes**
- SHA-256: `6700ce0f6d3516af9a63742b9e5acd1a7a3ae83b2494847feca11c73553ca0a1`
- ABI: `armeabi-v7a` (32-bit)
- Android target: API 28, compatible with the requested Android 11 device

## What changed from v1

Version 1's fixed controller transplant did not move correctly on the user's controller and its
camera was too fast. Version 2 replaces that fixed behavior with the visible, synthetic-touch
Control Studio proven in the Incredible Jack build.

- Visible first-run setup; no controller key is required to open it
- `PLACE` and `CHANGE` every PS3 button target
- True two-dimensional left-stick movement using both X and Y axes
- Right stick defaults to camera touch with a deliberately slow **40%** sensitivity
- Saved camera sensitivity adjustment from **15% to 150%**
- Optional right-stick mouse/cursor mode
- Separate assignable D-pad directions, including HAT X/Y input
- Multiple clicks per controller press
- Hide individual target markers or the complete small menu bar
- Assignable menu and cursor controls, with L3+R3 emergency menu recovery
- Smart controller input handling and saved mappings
- While mouse mode is active, Cross clicks at the cursor
- Long-press Cross enables scrolling with the right stick or directional controls

The mapper is injected directly into the unchanged launcher activity and emits the same synthetic
touch events as fingers on COD's own controls. Hooks are guarded so the original game path remains
available if the mapper declines or encounters an error.

## Integrity and signing

CI compiled the mapper, injected and verified all key, motion, touch, and lifecycle hooks, then
rebuilt and signed the APK with the established `bozpad` signing key. It also compared every
original `assets/` and `lib/` entry byte-for-byte; all **37** game payload entries remain unchanged.
