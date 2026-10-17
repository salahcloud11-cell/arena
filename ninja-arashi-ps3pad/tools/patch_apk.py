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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hook_smali  # noqa: E402

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

def check_dex_classes(apk, build_tools=None):
    """Prove the mapper really made it into the shipped classes.dex."""
    problems = []
    with zipfile.ZipFile(apk) as z:
        data = z.read("classes.dex")
    for desc in (b"Lcom/ps3mod/PS3Mapper;", b"Lcom/ps3mod/PS3BootActivity;"):
        if desc not in data:
            problems.append("%s is missing from classes.dex" % desc.decode())
    if not problems:
        log("    classes.dex contains com.ps3mod.PS3Mapper")
    return problems


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


def report_launcher(tree):
    """Find the activity the system will launch; it is the one we hook.

    The activity is NOT renamed and no Application class is injected: the game's
    own activity keeps its name, and the mapper is called from inside its smali.
    That is the mechanism the working Granny/CoD mods used.
    """
    log("== 2. locating the game's launcher activity")
    path = os.path.join(tree, "AndroidManifest.xml")
    xml = open(path, encoding="utf-8").read()
    launcher = None
    for m in re.finditer(r"<activity\b[^>]*?android:name=\"([^\"]+)\"(.*?)</activity>", xml, re.S):
        if "android.intent.action.MAIN" in m.group(2) and "android.intent.category.LAUNCHER" in m.group(2):
            launcher = m.group(1)
            break
    if not launcher:
        raise SystemExit("no launcher activity found in the manifest")
    log("    launcher activity (game): %s" % launcher)

    # Make our diagnostic boot screen the launcher: it is drawn before Unity,
    # so "is the mod installed and is the pad seen?" is answered visibly instead
    # of guessing from inside the game.
    version_label = "Ninja Arashi PS3v8"
    boot = ('<activity android:name="com.ps3mod.PS3BootActivity"'
            ' android:exported="true"'
            ' android:label="' + version_label + '"'
            ' android:screenOrientation="landscape"'
            ' android:configChanges="orientation|screenSize|keyboardHidden"'
            ' android:theme="@android:style/Theme.NoTitleBar.Fullscreen">'
            '<intent-filter>'
            '<action android:name="android.intent.action.MAIN"/>'
            '<category android:name="android.intent.category.LAUNCHER"/>'
            '</intent-filter>'
            '</activity>')

    # one launcher icon only: drop LAUNCHER from the Unity activity's own filter
    xml = xml.replace('<category android:name="android.intent.category.LAUNCHER"/>', '')
    if "com.ps3mod.PS3BootActivity" not in xml:
        m = re.search(r"<application\b[^>]*>", xml)
        if not m:
            raise SystemExit("no <application> tag")
        xml = xml[:m.end()] + boot + xml[m.end():]
    # the launcher label is what the user sees on the home screen, so it has to
    # carry the build version: that makes "did the new APK install?" answerable
    # without opening anything.
    xml2, n = re.subn(r'android:versionName="[^"]*"',
                      'android:versionName="1.4-ps3v8"', xml, count=1)
    if n:
        xml = xml2
        log("    versionName set to 1.4-ps3v8 in the manifest")

    # apktool rebuilds versionName from apktool.yml, which overrides the manifest
    yml_path = os.path.join(tree, "apktool.yml")
    if os.path.exists(yml_path):
        yml = open(yml_path, encoding="utf-8").read()
        if re.search(r"^\s*versionName:", yml, re.M):
            yml = re.sub(r"^(\s*versionName:\s*).*$", r"\g<1>1.4-ps3v8", yml, count=1, flags=re.M)
        else:
            yml = yml.replace("versionInfo:", "versionInfo:\n  versionName: 1.4-ps3v8", 1)
        open(yml_path, "w", encoding="utf-8").write(yml)
        log("    versionName set to 1.4-ps3v8 in apktool.yml")
    open(path, "w", encoding="utf-8").write(xml)
    log("    added com.ps3mod.PS3BootActivity as the launcher (label: %s)"
        % version_label)
    if "android.intent.category.LAUNCHER" not in xml:
        raise SystemExit("the rebuild has no launcher activity")
    return launcher


def jdk_tool(java, name):
    """javac/keytool live next to the java binary when a JDK path is used."""
    folder = os.path.dirname(java or "")
    if folder:
        cand = os.path.join(folder, name)
        if os.path.exists(cand):
            return cand
    return name


def java_compile(java_src_dir, work, java, android_jar, build_tools, stub_dir=None):
    log("== 3. compiling the mapper (javac + d8)")
    classes = os.path.join(work, "classes")
    dexdir = os.path.join(work, "dex")
    for d in (classes, dexdir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    sources = []
    for folder in (java_src_dir, stub_dir):
        if not folder or not os.path.isdir(folder):
            continue
        for root, _, files in os.walk(folder):
            for f in files:
                if f.endswith(".java"):
                    sources.append(os.path.join(root, f))
    if not sources:
        raise SystemExit("no java sources found in " + java_src_dir)
    javac = jdk_tool(java, "javac")
    try:
        run([javac, "--release", "8", "-encoding", "UTF-8", "-nowarn", "-classpath", android_jar,
             "-d", classes] + sorted(sources))
    except SystemExit:
        log("    (--release 8 unsupported, retrying with -source/-target 8)")
        run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-nowarn", "-classpath",
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
    # Take the manifest AND the resource table from the original APK: with both
    # present apktool decodes this stub exactly like it decoded the real APK in
    # step 1, so the dex -> smali conversion cannot hit resource resolution
    # errors, and no external dex tooling is needed.
    with zipfile.ZipFile(orig_apk) as z:
        manifest = z.read("AndroidManifest.xml")
        try:
            arsc = z.read("resources.arsc")
        except KeyError:
            arsc = None
    with zipfile.ZipFile(dummy, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest)
        if arsc is not None:
            z.writestr("resources.arsc", arsc)
        z.write(dex, "classes.dex")
    log("    stub APK for apktool: manifest%s + mapper dex"
        % (" + resources.arsc" if arsc is not None else ""))
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
    skipped = []
    for root, _, files in os.walk(smali):
        for f in files:
            if not f.endswith(".smali"):
                continue
            src = os.path.join(root, f)
            rel = os.path.relpath(src, smali)
            dst = os.path.join(dest_root, rel)
            if os.path.exists(dst):
                # a class the game already ships (the Unity activity stub):
                # the game's version must win, never ours
                skipped.append(rel)
                continue
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


def strip_old_signature(tree):
    log("== 5b. removing the game's old JAR signature")
    meta = os.path.join(tree, "META-INF")
    removed = []
    if os.path.isdir(meta):
        for name in sorted(os.listdir(meta)):
            upper = name.upper()
            if upper.endswith((".SF", ".RSA", ".DSA", ".EC")) or upper == "MANIFEST.MF":
                os.remove(os.path.join(meta, name))
                removed.append(name)
    log("    removed: %s" % (removed if removed else "nothing"))


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


def normalize_zip(apk_in, apk_out):
    """Rewrite the rebuilt APK with fixed entry timestamps, so that rebuilding
    the same sources yields the same file (apktool stamps entries with the
    checkout time, which would change the sha256 on every run)."""
    log("== 6b. normalising zip timestamps (reproducible output)")
    with zipfile.ZipFile(apk_in) as src:
        infos = src.infolist()
        with zipfile.ZipFile(apk_out, "w", zipfile.ZIP_DEFLATED, allowZip64=True,
                             compresslevel=9) as dst:
            for zi in infos:
                data = src.read(zi.filename)
                zi2 = zipfile.ZipInfo(zi.filename, (2020, 1, 1, 0, 0, 0))
                zi2.compress_type = zi.compress_type
                zi2.external_attr = zi.external_attr
                zi2.internal_attr = zi.internal_attr
                zi2.create_system = zi.create_system
                dst.writestr(zi2, data)
    log("    %d entries rewritten" % len(infos))


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


def selfcheck_shipped(apk_out, apktool, java, work):
    """Decode the SIGNED apk and confirm the mapper really is wired in.

    Everything up to here works on intermediate files; this checks the artifact
    the user installs, which is the only thing that matters.
    """
    out = os.path.join(work, "selfcheck")
    if os.path.isdir(out):
        shutil.rmtree(out)
    try:
        # -r: decode sources (smali) but keep resources raw - we only need the code
        run([java, "-Xmx2g", "-jar", apktool, "d", "-f", "-r", "-o", out, apk_out],
            check=False)
    except Exception:                            # noqa: BLE001
        return []
    found = {}
    for root, _, files in os.walk(out):
        for f in files:
            if f == "UnityPlayerActivity.smali":
                text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
                for call in ("onKey", "onMotion", "onTouch", "onGameActivity"):
                    if "Lcom/ps3mod/PS3Mapper;->" + call in text:
                        found[call] = True
    for call in ("onKey", "onMotion", "onTouch", "onGameActivity"):
        log("    shipped APK: %-14s %s" % (call, "wired" if found.get(call) else "MISSING"))
    missing = [c for c in ("onKey", "onMotion", "onTouch", "onGameActivity") if not found.get(c)]
    if missing:
        return ["the shipped APK does not call %s" % ", ".join(missing)]
    return []


def verify(apk_out, orig_apk, build_tools, tree=None):
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

    # Compare the game's payload by CONTENT, not by path, and only for the
    # parts that must never change: assets/ and lib/ (the engine, the game
    # metadata, the native libraries).  Resources are deliberately excluded --
    # apktool/aapt2 decode and re-encode them (and sometimes rename folders such
    # as res/drawable-hdpi-v4 -> res/drawable-hdpi), so their bytes legitimately
    # differ while their content does not.  AndroidManifest.xml is excluded as
    # well: we modify it on purpose.
    def payload(path):
        out = {}
        with zipfile.ZipFile(path) as z:
            for zi in z.infolist():
                n = zi.filename
                if n.endswith("/"):
                    continue
                if n == "classes.dex" or n == "AndroidManifest.xml" or n == "resources.arsc":
                    continue
                if n.startswith("res/") or n.startswith("META-INF/"):
                    continue
                if not (n.startswith("assets/") or n.startswith("lib/") or "/" not in n):
                    continue
                out.setdefault(hashlib.sha256(z.read(n)).hexdigest(), []).append(n)
        return out

    orig_payload = payload(orig_apk)
    out_payload = payload(apk_out)
    lost = [names for h, names in orig_payload.items() if h not in out_payload]
    if lost:
        problems.append("%d payload files are missing from the rebuild: %s"
                        % (len(lost), lost[:5]))
    n_orig = sum(len(v) for v in orig_payload.values())
    n_out = sum(len(v) for v in out_payload.values())
    log("    payload (assets/ + lib/): original %d files, rebuilt %d files" % (n_orig, n_out))
    if not lost:
        log("    every engine/asset file is byte-identical in the rebuild")
    with zipfile.ZipFile(orig_apk) as zo, zipfile.ZipFile(apk_out) as zz:
        log("    entries: original %d, rebuilt %d" % (len(zo.namelist()), len(zz.namelist())))

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
                if b"com/ps3mod/PS3Mapper" not in data:
                    problems.append("com.ps3mod.PS3Mapper is not in classes.dex")
                else:
                    log("    com.ps3mod.PS3Mapper is in classes.dex")
    try:
        badging = run([os.path.join(build_tools, "aapt"), "dump", "badging", apk_out])
        for line in badging.splitlines():
            if (line.startswith("package:") or line.startswith("launchable-activity:")
                    or line.startswith("sdkVersion:") or line.startswith("targetSdkVersion:")
                    or line.startswith("native-code:")):
                log("    " + line)
        if "com.blackpanther.ninjaarashi" not in badging:
            problems.append("package name changed in the rebuild")
        if "com.ps3mod.PS3BootActivity" not in badging:
            problems.append("the boot activity is not the launcher")
        if "1.4-ps3v8" not in badging:
            problems.append("versionName was not set")
        if "Ninja Arashi PS3v8" not in badging:
            problems.append("the launcher label does not carry the version")
        problems.extend(check_dex_classes(apk_out, build_tools))
    except SystemExit:
        problems.append("aapt badging failed")

    # the game's activity must still be declared (badging does not list it)
    if tree:
        try:
            manifest = open(os.path.join(tree, "AndroidManifest.xml"), encoding="utf-8").read()
            if "com.unity3d.player.UnityPlayerActivity" not in manifest:
                problems.append("the game's Unity activity disappeared from the manifest")
            else:
                log("    manifest still declares com.unity3d.player.UnityPlayerActivity")
            if manifest.count("android.intent.category.LAUNCHER") != 1:
                problems.append("expected exactly one LAUNCHER category")
            else:
                log("    exactly one launcher activity")
        except Exception as exc:                 # noqa: BLE001
            problems.append("could not read the rebuilt manifest: %s" % exc)

    if problems:
        log("    PROBLEMS: %s" % problems)
        return False
    log("    RESULT: APK OK")
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-in", required=True)
    ap.add_argument("--java-src", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "java"))
    ap.add_argument("--java-stub", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "java_stub"))
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
        report_launcher(tree)
    dex = (java_compile(a.java_src, a.work, a.java, a.android_jar, a.build_tools, None)
           if "java" not in skip else None)
    if "smali" not in skip:
        smali = dex_to_smali(a.apktool, a.java, a.work, dex, a.apk_in)
        merge_smali(tree, smali)

    if "hook" not in skip:
        log("== 5c. hooking the game's activity (smali)")
        name, desc, rel, sup, results = hook_smali.hook(tree)
        log("    %s (%s), superclass %s" % (name, desc, sup))
        log("    file: %s" % rel)
        bad = False
        for method, status in results:
            log("      %-28s %s" % (method, status))
            if status.startswith("skipped"):
                bad = True
            if status not in ("hooked", "added", "already hooked"):
                bad = True
        if bad:
            raise SystemExit("could not hook every dispatch method - refusing to ship")

        # assert the calls really are in the file
        with open(os.path.join(tree, rel), encoding="utf-8") as fh:
            text = fh.read()
        for method_name, param_desc, hook_name, _label, retbool in hook_smali.HOOKS:
            # boolean hooks pass the event, void hooks (lifecycle) do not
            if retbool:
                needle = ("Lcom/ps3mod/PS3Mapper;->%s(Landroid/app/Activity;%s)Z"
                          % (hook_name, param_desc))
            else:
                needle = ("Lcom/ps3mod/PS3Mapper;->%s(Landroid/app/Activity;)V"
                          % hook_name)
            if needle not in text:
                raise SystemExit("%s was not hooked into %s" % (method_name, rel))
        log("    all three dispatch methods call the mapper")
        log("    each call is wrapped in try/catch(Throwable): if the mapper class")
        log("    is missing or throws, the game's own dispatch runs unchanged.")
    unsig = os.path.join(a.work, "unsigned.apk")
    if "build" not in skip:
        strip_old_signature(tree)
        rebuild(a.apktool, a.java, tree, unsig)
    if "sign" not in skip:
        normalized = os.path.join(a.work, "unsigned-normalized.apk")
        normalize_zip(unsig, normalized)
        sign(a.build_tools, a.java, a.keydir, normalized, a.out)
    ok = verify(a.out, a.apk_in, a.build_tools, tree)
    if ok:
        log("== 9. self-check of the shipped APK")
        extra = selfcheck_shipped(a.out, a.apktool, a.java, a.work)
        if extra:
            log("    PROBLEMS: %s" % extra)
            ok = False
    if os.path.exists(a.out):
        with open(a.out + ".sha256", "w") as fh:
            fh.write("%s  %s\n" % (sha256(a.out), os.path.basename(a.out)))
        log("    sha256 written to %s.sha256" % a.out)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
