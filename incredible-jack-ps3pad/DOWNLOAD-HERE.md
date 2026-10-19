# Incredible Jack 1.36.6 + PS3 Smart Controller v3

## Download

**[Download Incredible-Jack-PS3-v3.apk from Google Drive](https://drive.google.com/file/d/1ii7gzjXZJ39mif1U59PgmE5WBIp4gkux/view)**

- Size: **88,671,546 bytes**
- SHA-256: `17b42fe5684f31a2ee8a91edc69f43fd3e23391629a75f95756c73969d61a5d3`
- Version: `1.36.6-ps3v3`
- Icon: **Incredible Jack PS3v3**
- Architectures: `armeabi-v7a` and `arm64-v8a`

The mod uses a different signature. Uninstall the original, v1 or v2 before installing v3. Back up
local game data first if it matters.

## Corrected mouse controls

- The right stick moves the cursor continuously while held; movement no longer depends on repeated
  controller events.
- The cursor is clamped inside the visible screen and kept above the game surface.
- While mouse mode is **ON**, press **CROSS (X)** to tap exactly at the cursor. X's separately mapped
  game target is suppressed, so one press cannot trigger both actions.
- R3 also clicks the cursor. **L3 + R3** still restores or hides the complete small menu bar.
- Turn mouse mode **OFF** in Control Studio when you want CROSS (X) to use its normal mapped target.

## Preserved features

- Smart Input supports normal Android gamepad codes, raw `BUTTON_1..12`, HAT-axis D-pad,
  direct/polled events and analog-trigger hysteresis.
- Control Studio can place, change and customize every PS3 control and the five extra buttons.
- Each mapped control supports 1x-5x multi-click.
- Touch-target markers can remain invisible, and mappings, mouse mode and bar visibility are saved.
- Synthetic touches are delivered to the game's own Cocos2d-x activity.
