#!/usr/bin/env python3
"""
build_apk.py -- build (and sign) the DualShock 3 version of ninja-arashi-1-4.apk.

    python3 tools/build_apk.py --apk-in /path/to/ninja-arashi-1-4.apk \
                               --out build/ninja-arashi-1-4-ps3pad.apk

What it does:
  1. builds libps3pad.so (unless build/libps3pad_arm64.so already exists)
  2. reads lib/arm64-v8a/libmain.so out of the original APK and adds a
     DT_NEEDED entry for it (tools/patch_libmain.py) - in memory
  3. copies every other entry of the original APK unchanged, drops the old
     META-INF signature files, adds lib/arm64-v8a/libps3pad.so
  4. signs the result with APK Signature Scheme v1 (JAR signing: MANIFEST.MF +
     CERT.SF + CERT.RSA).  The app targets API 29, so v1 is accepted by every
     Android release; a v2 signature block is deliberately not emitted.
  5. verifies: zip integrity, resources.arsc still stored, ELF sanity of both
     native libraries, every manifest/SF digest, and the PKCS#7 signature with
     openssl if the binary is available.

Only lib/arm64-v8a is touched.  The armeabi-v7a libraries are copied untouched,
so a 32-bit device installs and runs the unmodified game.
"""
import argparse, base64, datetime, hashlib, io, os, subprocess, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, "build")
sys.path.insert(0, HERE)

import patch_libmain                                     # noqa: E402
import build_lib                                         # noqa: E402

MAIN_SO = "lib/arm64-v8a/libmain.so"
PAD_SO = "lib/arm64-v8a/libps3pad.so"
DROP = {"META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"}


# --------------------------------------------------------------- signing
def make_key(key_path, cert_path):
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    os.makedirs(os.path.dirname(key_path), exist_ok=True)
    if os.path.exists(key_path) and os.path.exists(cert_path):
        return
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u"ps3pad mod"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, u"ps3pad")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365 * 30))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    with open(key_path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    print(f"   new signing key: {key_path}")


def digests(blob):
    return [("SHA-256-Digest", hashlib.sha256(blob).digest()),
            ("SHA1-Digest", hashlib.sha1(blob).digest())]


def build_manifest_sections(files):
    """files: [(name, data)] -> (MANIFEST.MF bytes, {name: manifest section bytes})"""
    out, sections = io.BytesIO(), {}
    out.write(b"Manifest-Version: 1.0\r\nCreated-By: 1.0 (ps3pad)\r\n\r\n")
    for name, data in files:
        sec = io.BytesIO()
        sec.write(b"Name: " + name.encode("utf-8") + b"\r\n")
        for alg, dg in digests(data):
            sec.write(alg.encode() + b": " + base64.b64encode(dg) + b"\r\n")
        sec.write(b"\r\n")
        sections[name] = sec.getvalue()
        out.write(sections[name])
    return out.getvalue(), sections


def build_sf(manifest, sections):
    out = io.BytesIO()
    out.write(b"Signature-Version: 1.0\r\nCreated-By: 1.0 (ps3pad)\r\n")
    for alg, dg in digests(manifest):
        out.write(alg.replace("-Digest", "-Digest-Manifest").encode() + b": "
                  + base64.b64encode(dg) + b"\r\n")
    out.write(b"\r\n")
    for name in sorted(sections):
        out.write(b"Name: " + name.encode("utf-8") + b"\r\n")
        for alg, dg in digests(sections[name]):
            out.write(alg.encode() + b": " + base64.b64encode(dg) + b"\r\n")
        out.write(b"\r\n")
    return out.getvalue()


def sign_pkcs7(sf_bytes, key_path, cert_path):
    from cryptography.hazmat.primitives.serialization import pkcs7, Encoding, load_pem_private_key
    from cryptography.x509 import load_pem_x509_certificate
    from cryptography.hazmat.primitives import hashes
    key = load_pem_private_key(open(key_path, "rb").read(), password=None)
    cert = load_pem_x509_certificate(open(cert_path, "rb").read())
    return (pkcs7.PKCS7SignatureBuilder().set_data(sf_bytes)
            .add_signer(cert, key, hashes.SHA256())
            .sign(Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature,
                                 pkcs7.PKCS7Options.Binary,
                                 pkcs7.PKCS7Options.NoCapabilities]))


def zip_add(zf, name, data, compress=zipfile.ZIP_DEFLATED):
    zi = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
    zi.compress_type = compress
    zi.external_attr = 0o100644 << 16
    zf.writestr(zi, data)


def build(apk_in, apk_out, keydir):
    pad_so = os.path.join(BUILD, "libps3pad_arm64.so")
    if not os.path.exists(pad_so):
        print("== building libps3pad.so")
        build_lib.build("arm64")

    print(f"== reading {apk_in}")
    src = zipfile.ZipFile(apk_in, "r")
    infos = src.infolist()
    if MAIN_SO not in src.namelist():
        raise SystemExit(f"{apk_in} does not contain {MAIN_SO}")

    print("== patching libmain.so")
    patched_main = patch_libmain.patch_bytes(src.read(MAIN_SO))
    problems = patch_libmain.verify_status(patched_main)
    if problems:
        raise SystemExit("patched libmain.so failed verification: " + "; ".join(problems))
    print(f"   {len(patched_main)} bytes, now depends on {patch_libmain.NEW_DEP}")

    print(f"== writing {apk_out}")
    payload, entries = [], []
    for zi in infos:
        name = zi.filename
        if name in DROP:
            continue
        data = patched_main if name == MAIN_SO else src.read(name)
        zi2 = zipfile.ZipInfo(name, zi.date_time)
        zi2.compress_type = zi.compress_type
        zi2.external_attr, zi2.internal_attr = zi.external_attr, zi.internal_attr
        zi2.create_system, zi2.comment = zi.create_system, zi.comment
        entries.append((zi2, data))
        payload.append((name, data))
    src.close()

    pad_data = open(pad_so, "rb").read()
    pad_zi = zipfile.ZipInfo(PAD_SO, (2020, 1, 1, 0, 0, 0))
    pad_zi.compress_type = zipfile.ZIP_DEFLATED      # native libs are compressed in this APK
    pad_zi.external_attr = 0o100644 << 16
    entries.append((pad_zi, pad_data))
    payload.append((PAD_SO, pad_data))

    key = os.path.join(keydir, "ps3pad.key")
    cert = os.path.join(keydir, "ps3pad.crt")
    make_key(key, cert)
    manifest, sections = build_manifest_sections(payload)
    sf = build_sf(manifest, sections)
    pkcs = sign_pkcs7(sf, key, cert)
    print(f"   MANIFEST.MF {len(manifest)} B, CERT.SF {len(sf)} B, CERT.RSA {len(pkcs)} B")
    for name, blob in (("META-INF/MANIFEST.MF", manifest), ("META-INF/CERT.SF", sf),
                       ("META-INF/CERT.RSA", pkcs)):
        zi = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o100644 << 16
        entries.append((zi, blob))

    os.makedirs(os.path.dirname(os.path.abspath(apk_out)), exist_ok=True)
    with zipfile.ZipFile(apk_out, "w", zipfile.ZIP_DEFLATED, allowZip64=True, compresslevel=9) as out:
        for zi, data in entries:
            out.writestr(zi, data)
    print(f"   {len(entries)} entries, {os.path.getsize(apk_out):,} bytes")
    return apk_out


# -------------------------------------------------------------- verifying
def verify(apk):
    from elftools.elf.elffile import ELFFile
    ok = True
    z = zipfile.ZipFile(apk, "r")
    names = z.namelist()
    print(f"   entries: {len(names)}   size: {os.path.getsize(apk):,} B")
    for required in (PAD_SO, MAIN_SO, "lib/armeabi-v7a/libmain.so", "classes.dex",
                     "resources.arsc", "assets/bin/Data/Managed/Metadata/global-metadata.dat",
                     "META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"):
        if required not in names:
            print(f"   MISSING {required}"); ok = False

    if z.testzip():
        print("   CORRUPT zip entry"); ok = False
    else:
        print("   zip integrity: OK")
    arsc = z.getinfo("resources.arsc")
    if arsc.compress_type != zipfile.ZIP_STORED:
        print("   WARNING: resources.arsc is compressed"); ok = False
    if arsc.file_size > 0xffffffff:
        print("   WARNING: resources.arsc needs zip64 (Android 11+ only)"); ok = False

    e = ELFFile(io.BytesIO(z.read(MAIN_SO)))
    needed = [t.needed for t in e.get_section_by_name('.dynamic').iter_tags()
              if t.entry.d_tag == 'DT_NEEDED']
    print(f"   patched libmain.so DT_NEEDED: {needed}")
    if patch_libmain.NEW_DEP not in needed:
        print("   libmain.so does not reference libps3pad.so"); ok = False
    pe = ELFFile(io.BytesIO(z.read(PAD_SO)))
    und = sorted(s.name for s in pe.get_section_by_name('.dynsym').iter_symbols()
                 if s['st_shndx'] == 'SHN_UNDEF' and s.name)
    print(f"   libps3pad.so: {pe.header.e_machine}, imports {und}")

    # v1 signature: recompute every digest independently of the signing code
    manifest, sf, pkcs = (z.read("META-INF/MANIFEST.MF"), z.read("META-INF/CERT.SF"),
                          z.read("META-INF/CERT.RSA"))

    def parse(blob):
        out = []
        for chunk in blob.decode("utf-8", "replace").split("\r\n\r\n"):
            if not chunk.strip():
                continue
            h, key = {}, None
            for line in chunk.split("\r\n"):
                if line.startswith(" ") and key:
                    h[key] += line[1:]
                elif ":" in line:
                    k, v = line.split(":", 1)
                    key = k.strip(); h[key] = v.strip()
            out.append(h)
        return out

    msections = parse(manifest)
    ssections = parse(sf)
    mby = {s["Name"]: s for s in msections if "Name" in s}
    sby = {s["Name"]: s for s in ssections if "Name" in s}
    main, sfmain = (msections[0] if msections else {}), (ssections[0] if ssections else {})
    print(f"   MANIFEST.MF: {len(mby)} entry sections (version {main.get('Manifest-Version')})")
    bad = 0
    for name in names:
        if name.startswith("META-INF/"):
            continue
        data = z.read(name)
        if name not in mby:
            print(f"   {name} missing from MANIFEST.MF"); bad += 1; continue
        for alg, h in (("SHA-256-Digest", hashlib.sha256), ("SHA1-Digest", hashlib.sha1)):
            if mby[name].get(alg) and base64.b64encode(h(data).digest()).decode() != mby[name][alg]:
                print(f"   {name}: {alg} mismatch"); bad += 1
        sec = io.BytesIO()
        sec.write(b"Name: " + name.encode() + b"\r\n")
        for alg, dg in digests(data):
            sec.write(alg.encode() + b": " + base64.b64encode(dg) + b"\r\n")
        sec.write(b"\r\n")
        if name in sby:
            for alg, h in (("SHA-256-Digest", hashlib.sha256), ("SHA1-Digest", hashlib.sha1)):
                if sby[name].get(alg) and base64.b64encode(h(sec.getvalue()).digest()).decode() != sby[name][alg]:
                    print(f"   CERT.SF section {name}: {alg} mismatch"); bad += 1
    for alg, h in (("SHA-256-Digest-Manifest", hashlib.sha256), ("SHA1-Digest-Manifest", hashlib.sha1)):
        if sfmain.get(alg) and base64.b64encode(h(manifest).digest()).decode() != sfmain[alg]:
            print(f"   CERT.SF {alg} mismatch"); bad += 1
    print(f"   digest checks: {'OK' if not bad else str(bad) + ' FAILURES'}")
    if bad:
        ok = False

    tmp = "/tmp/_ps3pad_sigcheck"
    os.makedirs(tmp, exist_ok=True)
    open(f"{tmp}/sf.bin", "wb").write(sf)
    open(f"{tmp}/sig.der", "wb").write(pkcs)
    try:
        r = subprocess.run(["openssl", "cms", "-verify", "-inform", "DER", "-in", f"{tmp}/sig.der",
                            "-content", f"{tmp}/sf.bin", "-binary", "-noverify", "-out", "/dev/null"],
                           capture_output=True, text=True)
        print("   openssl cms -verify:", (r.stderr.strip() or r.stdout.strip() or "no output"))
        if r.returncode != 0:
            ok = False
    except FileNotFoundError:
        print("   openssl not available, skipped PKCS#7 check")

    sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    print("   sha256:", sha)
    print("   RESULT:", "APK OK" if ok else "APK PROBLEMS FOUND")
    return ok


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-in", required=True, help="the original ninja-arashi-1-4.apk")
    ap.add_argument("--out", default=os.path.join(BUILD, "ninja-arashi-1-4-ps3pad.apk"))
    ap.add_argument("--keydir", default=os.path.join(BUILD, "keys"))
    a = ap.parse_args()
    build(a.apk_in, a.out, a.keydir)
    print("== verifying", a.out)
    sys.exit(0 if verify(a.out) else 1)
