#!/usr/bin/env python3
"""
build_lib.py -- cross-compile libps3pad.so for Android.

No Android NDK is required: the zig toolchain ships its own libc/compiler-rt for
the musl targets, and this library is built freestanding (-nostdlib) anyway, so
no libc is linked in.  Every libc function it uses is resolved by the Android
linker at load time, exactly as for any app .so.

    python3 tools/build_lib.py            # arm64-v8a (the release build)
    python3 tools/build_lib.py --v7a      # armeabi-v7a (not shipped yet)

Requires: pip install ziglang   (or a zig binary on $PATH)
"""
import argparse, os, subprocess, sys, glob

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "src")
BUILD = os.path.join(ROOT, "build")

TARGETS = {
    "arm64": ("aarch64-linux-musl", "libps3pad_arm64.so"),
    "v7a":   ("arm-linux-musleabi", "libps3pad_arm.so"),
}


def find_zig():
    for cand in (os.environ.get("ZIG"), "zig",
                 glob.glob("/usr/local/lib/python3.11/dist-packages/ziglang/zig"),
                 glob.glob("/usr/local/lib/python*/dist-packages/ziglang/zig")):
        if not cand:
            continue
        cand = cand[0] if isinstance(cand, list) else cand
        try:
            subprocess.run([cand, "version"], capture_output=True, check=True)
            return cand
        except Exception:
            continue
    raise SystemExit("zig not found - install it with: pip install ziglang")


def build(arch="arm64"):
    target, name = TARGETS[arch]
    zig = find_zig()
    os.makedirs(BUILD, exist_ok=True)
    out = os.path.join(BUILD, name)
    cmd = [zig, "cc", "-target", target,
           "-shared", "-fPIC", "-O2", "-nostdlib", "-s",
           "-fno-stack-protector", "-fvisibility=hidden",
           "-fno-builtin-memcpy", "-fno-builtin-memset",
           "-Wl,-soname,libps3pad.so",
           "-o", out,
           os.path.join(SRC, "ps3pad.c"),
           os.path.join(SRC, "hook_stub.S")]
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    for line in r.stderr.splitlines():
        if "warning:" in line and "built-in function" not in line:
            print("   ", line)
    if r.returncode != 0:
        print(r.stderr)
        raise SystemExit("build failed")
    print(f"  built {out} ({os.path.getsize(out)} bytes)")
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--v7a", action="store_true", help="build armeabi-v7a instead of arm64")
    a = ap.parse_args()
    build("v7a" if a.v7a else "arm64")
