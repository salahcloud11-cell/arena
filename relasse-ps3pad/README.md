# COD:BOZ app-release — Full PS3 Controller v1

## Download

**[Download COD-BOZ-app-release-PS3-v1.apk](https://drive.google.com/file/d/1Dh0-ShnlTgYCgawixococ4ErCtPLtCNh/view)**

- Source: Drive `app-release.apk`, package `com.activision.boz`
- Source version: `1.0.8.1kotlin`
- Mod version: `1.0.8.1kotlin-ps3v1`
- Size: **43,904,178 bytes**
- SHA-256: `d76b0cb8a704039c6994da74d171fb4de76ffdec560799822ffc60538113bbd8`
- ABI: `armeabi-v7a` (32-bit)

## Controller implementation

This build transplants the proven BOZ v8 Marmalade controller engine rather than using a generic
key mapper. The launcher is still `com.activision.boz.Main`; guarded hooks call the controller
before falling back to the original game.

- Left stick: analog movement
- Right stick: smooth camera/look using DS3 Z/RZ with alternate-axis handling
- D-pad: movement/navigation
- Cross, Circle, Square and Triangle
- L1/R1 and digital or analog L2/R2
- Start, Select, L3 and R3
- Native Marmalade key injection and synthetic touch where the game requires it
- Controller events are consumed so one press does not also trigger an unrelated game action

The workflow verified both shipped hooks after rebuilding, verified the APK signatures, and compared
all original `assets/` and `lib/` entries byte-for-byte. All **37** packaged game assets/native
library entries are preserved from the supplied APK.

It is signed with the existing BOZ controller key (`bozpad`), matching the established BOZ mod line.
