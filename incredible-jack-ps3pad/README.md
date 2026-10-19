# Incredible Jack — PS3 controller mod

A Java synthetic-touch controller mapper injected into the original Cocos2d-x launcher
`org.cocos2dx.incrediblejack.incrediblejack`.

- Source: Google Drive `Incredible Jack.apk`, 87,866,720 bytes, package
  `com.chillingo.incrediblejack.android.rowgplay1`, version 1.36.6.
- Output: **[Incredible-Jack-PS3-v5.apk](DOWNLOAD-HERE.md)**.
- Both `armeabi-v7a` and `arm64-v8a` game libraries and every asset are verified byte-identical.
- The mapper lives in the small second dex; all five original dex files are preserved.
- Touch targets are invisible by default and fully customizable.
- v5 fixes cursor/menu hotkey capture inside Control Studio and removes ad/network permissions and
  automatic ad components. It preserves scrolling, selective original-button masking, Smart Input,
  invisible targets and multi-click mappings.

The CI pipeline downloads the source, decodes all five dex files, compiles the mapper, hooks
key/motion/touch/lifecycle dispatch in the real launcher, rebuilds, signs, and then decodes the
shipped APK to prove all four hooks are present.
