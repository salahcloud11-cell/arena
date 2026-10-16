#!/usr/bin/env python3
"""
patch_apk.py -- build the PS3-controller version of Ninja Arashi 1.4.

This is the 32-bit-safe path: it adds no native code at all.  The pad is read
from Android and replayed as synthetic touches by a small Java class compiled
into the game's own classes.dex, so the APK keeps working on armeabi-v7a and
arm64-v8a phones alike.  (The earlier libps3pad.so route in this folder only
covered arm64 and is kept for reference.)

Pipeline:
  1. apktool d                       original APK -> decoded tree
  2. patch AndroidManifest.xml       android:name="com.ps3mod.PS3App" on <application>
  3. javac + d8                      java/ -> classes.dex
  4. dex -> smali                    via apktool on a small dummy APK (no extra tools)
  5. smali -> tree/smali/com/ps3mod  merged with the game's own code
  6. apktool b                       rebuild
  7. zipalign + apksigner            aligned, signed with v1 + v2
  8. verify                          assets byte-identical, signature, badging

Everything is driven from CI (needs a JDK and the Android build-tools); it also
runs locally if those are installed.
"""
import argparse
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

KEY_ALIAS = "ps3mod"
KS_PASS = "ps3mod"
KEYSTORE_PASS = "ps3mod"

# assets that must come out of the rebuild byte-for-byte identical
CRITICAL_ASSETS = [
    "assets/bin/Data/Managed/Metadata/global-metadata.dat",
    "lib/arm64-v8a/libil2cpp.so",
    "lib/armeabi-v7a/libil2cpp.so",
    "lib/arm64-v8a/libunity.so",
    "lib/armeabi-v7a/libunity.so",
    "classes.dex",              # replaced by design, checked separately
]


def log(msg):
    print(msg, flush=True)


def run(cmd, cwd=None, check=True, logfile=None):
    printable = " ".join(cmd) if isinstance(cmd, list) else cmd
    log("    $ " + printable)
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    out = (p.stdout or "") + (p.stderr or "")
    for line in out.splitlines():
        log("      " + line)
    if logfile:
        with open(logfile, "a") as fh:
            fh.write("$ " + printable + "\n" + out + "\n")
    if check and p.returncode != 0:
        raise SystemExit("command failed (%d): %s" % (p.returncode, printable))
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def zip_entry_sha(apk, name):
    with zipfile.ZipFile(apk) as z:
        return hashlib.sha256(z.read(name)).hexdigest()


# --------------------------------------------------------------------- steps
def report_dexes(apk, label):
    log("    %s dex method counts:" % label)
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.match(r"classes\d*\.dex$", n))
        total = 0
        for n in dexes:
            data = z.read(n)
            path = "/tmp/_count.dex"
            with open(path, "wb") as fh:
                fh.write(data)
            count = count_dex_methods(path)
            total += count or 0
            log("      %s: %s methods (%d bytes)" % (n, count, len(data)))
        log("      total: %d methods (limit is 65536 per dex)" % total)
        return total


def decode(apktool, java, apk_in, tree):
    log("== 1. decoding the original APK (apktool)")
    report_dexes(apk_in, "original")
    if os.path.isdir(tree):
        shutil.rmtree(tree)
    run([java, "-Xmx3g", "-jar", apktool, "d", "-f", "-o", tree, apk_in])


def patch_manifest(tree):
    log("== 2. adding the Application class to AndroidManifest.xml")
    path = os.path.join(tree, "AndroidManifest.xml")
    xml = open(path, encoding="utf-8").read()
    if 'android:name="com.ps3mod.PS3App"' in xml:
        log("    already present")
        return
    old = re.search(r"<application\b[^>]*?android:name=\"([^\"]*)\"", xml)
    if old:
        log("    NOTE: original Application class was %s (replaced)" % old.group(1))
        xml = re.sub(r"(<application\b[^>]*?)\s+android:name=\"[^\"]*\"", r"\1", xml, count=1)
    new, n = re.subn(r"(<application\b)", r"\1 android:name=\"com.ps3mod.PS3App\"",
                     xml, count=1)
    if not n:
        raise SystemExit("could not find the <application> tag")
    open(path, "w", encoding="utf-8").write(new)
    log("    android:name=\"com.ps3mod.PS3App\" injected")


def jdk_tool(java, name):
    """javac/keytool live next to the java binary when a JDK path is used."""
    folder = os.path.dirname(java or "")
    if folder:
        cand = os.path.join(folder, name)
        if os.path.exists(cand):
            return cand
    return name


def java_compile(java_src_dir, work, java, android_jar, build_tools):
    log("== 3. compiling the mapper (javac + d8)")
    classes = os.path.join(work, "classes")
    dexdir = os.path.join(work, "dex")
    for d in (classes, dexdir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    sources = []
    for root, _, files in os.walk(java_src_dir):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    if not sources:
        raise SystemExit("no java sources found in " + java_src_dir)
    javac = jdk_tool(java, "javac")
    try:
        run([javac, "--release", "8", "-nowarn", "-classpath", android_jar,
             "-d", classes] + sorted(sources))
    except SystemExit:
        log("    (--release 8 unsupported, retrying with -source/-target 8)")
        run([javac, "-source", "8", "-target", "8", "-nowarn", "-classpath",
             android_jar, "-d", classes] + sorted(sources))
    class_files = []
    for root, _, files in os.walk(classes):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    run([os.path.join(build_tools, "d8"), "--min-api", "16", "--output", dexdir] + sorted(class_files))
    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex):
        raise SystemExit("d8 produced no classes.dex")
    log("    mapper dex: %d bytes" % os.path.getsize(dex))
    return dex


def dex_to_smali(apktool, java, work, dex, orig_apk):
    """apktool can decode a dex when it sits in a minimal APK, so no extra
    dex tooling is needed."""
    log("== 4. converting the mapper dex to smali")
    dummy = os.path.join(work, "dummy.apk")
    with zipfile.ZipFile(orig_apk) as z:
        manifest = z.read("AndroidManifest.xml")
    with zipfile.ZipFile(dummy, "w") as z:
        z.writestr("AndroidManifest.xml", manifest)
        z.write(dex, "classes.dex")
    out = os.path.join(work, "dummy-out")
    if os.path.isdir(out):
        shutil.rmtree(out)
    run([java, "-Xmx2g", "-jar", apktool, "d", "-f", "-o", out, dummy])
    smali = os.path.join(out, "smali")
    if not os.path.isdir(smali):
        raise SystemExit("apktool did not produce smali for the mapper")
    return smali


def merge_smali(tree, smali):
    log("== 5. merging the mapper into the game's smali tree")
    dest_root = os.path.join(tree, "smali")
    copied = 0
    for root, _, files in os.walk(smali):
        for f in files:
            if not f.endswith(".smali"):
                continue
            src = os.path.join(root, f)
            rel = os.path.relpath(src, smali)
            dst = os.path.join(dest_root, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            copied += 1
    log("    %d smali files copied into %s" % (copied, dest_root))
    if not copied:
        raise SystemExit("nothing was copied - the mapper would not be in the APK")


def count_dex_methods(path):
    with open(path, "rb") as fh:
        head = fh.read(112)
    if head[:4] != b"dex\n":
        return None
    return struct.unpack_from("<I", head, 88)[0]


def rebuild(apktool, java, tree, unsig):
    log("== 6. rebuilding (apktool b)")
    run([java, "-Xmx3g", "-jar", apktool, "b", "-o", unsig, tree])
    n = count_dex_methods(unsig) if os.path.exists(unsig) else None
    with zipfile.ZipFile(unsig) as z:
        names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
    log("    dex files in the rebuild: %s" % names)
    for name in names:
        with zipfile.ZipFile(unsig) as z:
            data = z.read(name)
        with open(os.path.join(os.path.dirname(unsig), name), "wb") as fh:
            fh.write(data)
        path = os.path.join(os.path.dirname(unsig), name)
        log("    %s: %d methods" % (name, count_dex_methods(path)))


def sign(build_tools, java, keydir, apk_in, apk_out):
    log("== 7. zipalign + apksigner")
    os.makedirs(keydir, exist_ok=True)
    ks = os.path.join(keydir, "ps3mod.keystore")
    if not os.path.exists(ks):
        run([jdk_tool(java, "keytool"), "-genkeypair", "-keystore", ks, "-alias", KEY_ALIAS,
             "-storepass", KEYSTORE_PASS, "-keypass", KS_PASS,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-dname", "CN=ps3pad, O=ps3pad"])
    aligned = apk_in + ".aligned"
    run([os.path.join(build_tools, "zipalign"), "-f", "-p", "4", apk_in, aligned])
    run([os.path.join(build_tools, "apksigner"), "sign",
         "--ks", ks, "--ks-key-alias", KEY_ALIAS,
         "--ks-pass", "pass:" + KEYSTORE_PASS, "--key-pass", "pass:" + KS_PASS,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "false",
         "--out", apk_out, aligned])
    return apk_out


def verify(apk_out, orig_apk, build_tools):
    log("== 8. verification")
    problems = []
    if not os.path.exists(apk_out):
        raise SystemExit("no output APK")
    log("    size: %d bytes" % os.path.getsize(apk_out))

    out = run([os.path.join(build_tools, "apksigner"), "verify", "--verbose", "--print-certs", apk_out])
    if "Verified using v1 scheme (JAR signing): true" not in out:
        problems.append("v1 signature missing")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in out:
        problems.append("v2 signature missing")

    # the game data must survive the round trip untouched
    for name in CRITICAL_ASSETS:
        if name == "classes.dex":
            continue
        try:
            a = zip_entry_sha(orig_apk, name)
            b = zip_entry_sha(apk_out, name)
        except KeyError:
            problems.append("missing entry in output: " + name)
            continue
        if a != b:
            problems.append("asset changed: " + name)
        else:
            log("    unchanged: %s" % name)

    with zipfile.ZipFile(apk_out) as z:
        bad = z.testzip()
        if bad:
            problems.append("corrupt zip entry " + bad)
        names = z.namelist()
        dexes = [n for n in names if re.match(r"classes\d*\.dex$", n)]
        log("    dex files: %s" % dexes)
        for n in dexes:
            with zipfile.ZipFile(apk_out) as zz:
                data = zz.read(n)
            tmp = "/tmp/_dexcheck.dex"
            with open(tmp, "wb") as fh:
                fh.write(data)
            log("    %s: %s methods" % (n, count_dex_methods(tmp)))
        # our class must be in the first dex
        for n in dexes:
            if n == "classes.dex":
                data = z.read(n)
                if b"com/ps3mod/PS3App" not in data:
                    problems.append("com.ps3mod.PS3App is not in classes.dex")
                else:
                    log("    com.ps3mod classes are in classes.dex")
    try:
        badging = run([os.path.join(build_tools, "aapt"), "dump", "badging", apk_out])
        for line in badging.splitlines():
            if (line.startswith("package:") or line.startswith("launchable-activity:")
                    or line.startswith("sdkVersion:") or line.startswith("targetSdkVersion:")
                    or line.startswith("native-code:")):
                log("    " + line)
        if "com.blackpanther.ninjaarashi" not in badging:
            problems.append("package name changed in the rebuild")
    except SystemExit:
        problems.append("aapt badging failed")

    if problems:
        log("    PROBLEMS: %s" % problems)
        return False
    log("    RESULT: APK OK")
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-in", required=True)
    ap.add_argument("--java-src", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "java"))
    ap.add_argument("--apktool", required=True)
    ap.add_argument("--android-jar", required=True)
    ap.add_argument("--build-tools", required=True)
    ap.add_argument("--java", default="java")
    ap.add_argument("--work", required=True)
    ap.add_argument("--keydir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--skip-steps", default="")
    a = ap.parse_args()

    os.makedirs(a.work, exist_ok=True)
    tree = os.path.join(a.work, "tree")
    skip = set(s for s in a.skip_steps.split(",") if s)
    log("== original APK: %s (%d bytes, sha256 %s)" % (a.apk_in, os.path.getsize(a.apk_in), sha256(a.apk_in)[:16]))

    if "decode" not in skip:
        decode(a.apktool, a.java, a.apk_in, tree)
    if "manifest" not in skip:
        patch_manifest(tree)
    dex = java_compile(a.java_src, a.work, a.java, a.android_jar, a.build_tools) if "java" not in skip else None
    if "smali" not in skip:
        smali = dex_to_smali(a.apktool, a.java, a.work, dex, a.apk_in)
        merge_smali(tree, smali)
    unsig = os.path.join(a.work, "unsigned.apk")
    if "build" not in skip:
        rebuild(a.apktool, a.java, tree, unsig)
    if "sign" not in skip:
        sign(a.build_tools, a.java, a.keydir, unsig, a.out)
    ok = verify(a.out, a.apk_in, a.build_tools)
    if os.path.exists(a.out):
        with open(a.out + ".sha256", "w") as fh:
            fh.write("%s  %s\n" % (sha256(a.out), os.path.basename(a.out)))
        log("    sha256 written to %s.sha256" % a.out)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
