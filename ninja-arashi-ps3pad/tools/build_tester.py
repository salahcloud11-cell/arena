#!/usr/bin/env python3
"""Build the tiny PS3 test/verification app (no resources, no Gradle).

Why it exists
-------------
Six builds in a row were reported as "nothing happens at all".  That report is
identical whether the mod is missing from the phone or the mod is running but
blind, and there was no way to tell the two apart from the build machine.  This
APK is 100 KB instead of 65 MB, installs next to the game (its own package), and
prints, in large text:

  1. the versionName / launcher activity of the installed Ninja Arashi,
  2. every key the pad sends to this app (event path),
  3. whether the hidden InputManager.getKeyCodeState() reader works on the
     device (the API v7/v8 depend on).

Usage (CI does this automatically):
  python3 build_tester.py --android-jar .../android.jar \
      --build-tools .../build-tools/34.0.0 --java .../bin/java \
      --keydir .../keys --out ps3-tester.apk
"""
import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)          # ninja-arashi-ps3pad/
TESTER = os.path.join(ROOT, "tester")


def run(cmd, cwd=None):
    print("+ " + " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], cwd=cwd)
    if r.returncode != 0:
        raise SystemExit("FAILED (%d): %s" % (r.returncode, " ".join(str(c) for c in cmd)))


def jdk_tool(java, name):
    """javac/keytool sit next to the java binary when a JDK is used."""
    d = os.path.dirname(os.path.abspath(java))
    cand = os.path.join(d, name)
    if os.path.exists(cand):
        return cand
    return name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--android-jar", required=True)
    ap.add_argument("--build-tools", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--keydir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", default=None)
    a = ap.parse_args()

    work = a.work or os.path.join(os.path.dirname(os.path.abspath(a.out)), "testerwork")
    if os.path.isdir(work):
        shutil.rmtree(work)
    os.makedirs(work)
    classes = os.path.join(work, "classes")
    dexdir = os.path.join(work, "dex")
    os.makedirs(classes)
    os.makedirs(dexdir)

    javac = jdk_tool(a.java, "javac")
    sources = []
    for root, _, files in os.walk(os.path.join(TESTER, "src")):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    if not sources:
        raise SystemExit("no tester sources")

    print("== javac (%d files)" % len(sources))
    try:
        run([javac, "--release", "8", "-encoding", "UTF-8", "-nowarn",
             "-classpath", a.android_jar, "-d", classes] + sorted(sources))
    except SystemExit:
        run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-nowarn",
             "-classpath", a.android_jar, "-d", classes] + sorted(sources))

    class_files = []
    for root, _, files in os.walk(classes):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    print("== d8 (%d classes)" % len(class_files))
    run([os.path.join(a.build_tools, "d8"), "--min-api", "16", "--output", dexdir]
        + sorted(class_files))

    unsigned = os.path.join(work, "unsigned.apk")
    aapt = os.path.join(a.build_tools, "aapt")
    aapt2 = os.path.join(a.build_tools, "aapt2")
    manifest = os.path.join(TESTER, "AndroidManifest.xml")
    print("== package")
    if os.path.exists(aapt):
        run([aapt, "package", "-f", "-M", manifest, "-I", a.android_jar,
             "-F", unsigned])
        run([aapt, "add", unsigned, "classes.dex"], cwd=dexdir)
    else:
        # aapt2 fallback: link then zip the dex in
        run([aapt2, "link", "-o", unsigned, "-I", a.android_jar,
             "--manifest", manifest, "--min-sdk-version", "16",
             "--target-sdk-version", "29"])
        import zipfile
        with zipfile.ZipFile(unsigned, "a") as z:
            z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")

    aligned = os.path.join(work, "aligned.apk")
    run([os.path.join(a.build_tools, "zipalign"), "-f", "-p", "4", unsigned, aligned])

    keystore = os.path.join(a.keydir, "ps3mod.keystore")
    apksigner = os.path.join(a.build_tools, "apksigner")
    print("== sign")
    run([apksigner, "sign", "--ks", keystore, "--ks-pass", "pass:ps3mod",
         "--key-pass", "pass:ps3mod", "--ks-key-alias", "ps3mod",
         "--out", a.out, aligned])
    run([apksigner, "verify", "--print-certs", a.out])

    size = os.path.getsize(a.out)
    print("tester apk: %s (%d bytes)" % (a.out, size))
    if size < 20000:
        raise SystemExit("tester apk suspiciously small")
    if os.path.exists(aapt):
        run([aapt, "dump", "badging", a.out])
    return 0


if __name__ == "__main__":
    sys.exit(main())
