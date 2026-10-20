# COD:BOZ — PlayStation-style PS3 Controls v3

## Download

**[Download COD-BOZ-PS3-PlayStation-v3.apk](https://drive.google.com/file/d/1_evNEiMiexHQe0Zy2q_VYwdxrw1KYta9/view)**

- Package: `com.activision.boz`
- Launcher: `com.activision.boz.Main`
- Version: `1.0.8.1kotlin-ps3v3`
- Size: **43,932,908 bytes**
- SHA-256: `7364e9aabb0d582a26ba1379a442a9f14cb408c98dc7872c8c197a6c499d38b8`
- ABI: `armeabi-v7a` (32-bit)

## PlayStation-style stick corrections

- The left stick now has a large circular reach based on the short screen edge rather than a tiny percentage of each axis.
- Full left-stick tilt reaches COD's outer virtual-stick ring for full-speed running.
- Left-stick run reach defaults to **24%** and is adjustable from **12%–35%** in Control Studio.
- The right stick is now a continuous 60 Hz relative camera swipe, not one fixed touch displacement per Android event.
- Holding the right stick keeps turning smoothly, as on a console, instead of moving the view only one step.
- Camera swipes automatically recenter before reaching a screen edge.
- A console-style analog curve gives fine aim near the center and fast turning at full tilt.
- The right-axis detector requires a complete RX/RY pair, preventing one-dimensional movement from unused DS3 axes.
- Camera sensitivity remains adjustable from **15%–150%**, with a balanced **55%** default for fresh installs.

## Control Studio

Every PS3 control remains customizable with visible first-run setup, `PLACE` and `CHANGE`, saved
mappings, multi-click, optional mouse mode, hideable targets and menu bar, assignable menu/cursor
buttons, and L3+R3 emergency menu recovery.

CI verified the package, launcher, signing identity, mapper hooks, and byte-for-byte preservation of
all **37** original `assets/` and `lib/` game payload entries.
