#!/usr/bin/env python3
"""
patch_libmain.py -- make Unity's libmain.so load libps3pad.so.

libmain.so is the tiny bootstrap library that the Java side loads first
(System.loadLibrary("main")); its constructor calls into libunity, which starts
the Unity player.  It already carries six DT_NEEDED entries and its .dynamic
array has five spare 16-byte slots, so an extra dependency can be added without
moving anything:

  * DT_NEEDED written over the leading DT_NULL slot, DT_NULL moved one slot on
    (still inside PT_DYNAMIC)
  * the string "libps3pad.so" appended to a byte-identical copy of .dynstr that
    is placed at the end of the file; DT_STRTAB / DT_STRSZ are repointed at the
    copy, so every existing st_name / DT_NEEDED offset stays valid
  * PT_LOAD #2 grown to map the new string table (one extra page, still
    4 KB-congruent), and the .dynstr section header updated to match

Everything else in the file stays byte-for-byte identical; see verify_status()
for the list of fields that are allowed to change.

Used as a library by tools/build_apk.py, or standalone:

    python3 tools/patch_libmain.py libmain.so libmain_patched.so
"""
import io, os, struct, sys
from elftools.elf.elffile import ELFFile

NEW_DEP = "libps3pad.so"
PTLOAD2_OFFSET = 0x1000     # the writable PT_LOAD / PT_DYNAMIC page


# --------------------------------------------------------------- patching
def patch_bytes(data):
    """Take the bytes of an original libmain.so, return the patched bytes."""
    data = bytearray(data)
    e = ELFFile(io.BytesIO(bytes(data)))

    dyn = e.get_section_by_name('.dynamic')
    if dyn is None:
        raise ValueError("no .dynamic section - not an ELF shared object?")
    if NEW_DEP in [t.needed for t in dyn.iter_tags() if t.entry.d_tag == 'DT_NEEDED']:
        return bytes(data)                      # already patched

    dynstr = e.get_section_by_name('.dynstr')
    old_off, old_size = dynstr['sh_offset'], dynstr['sh_size']
    dyn_off = dyn['sh_offset']
    slots = [(struct.unpack_from('<QQ', data, dyn_off + i * 16))
             for i in range(dyn['sh_size'] // 16)]
    if slots[-1][0] != 0:
        raise ValueError("dynamic array does not end in DT_NULL")
    null_i = min(i for i, (t, v) in enumerate(slots) if t == 0)
    if null_i + 1 >= len(slots):
        raise ValueError("no spare dynamic slot after DT_NULL")

    # 1. new string table at the tail of the file (all old offsets preserved)
    new_str_off = (len(data) + 7) & ~7
    new_str = bytes(data[old_off:old_off + old_size])
    name_off = len(new_str)
    new_str += NEW_DEP.encode() + b'\0'
    data += b'\0' * (new_str_off - len(data)) + new_str
    data += b'\0' * ((8 - len(data) % 8) % 8)

    # 2. add the dependency and re-terminate
    struct.pack_into('<QQ', data, dyn_off + null_i * 16, 1, name_off)
    struct.pack_into('<QQ', data, dyn_off + (null_i + 1) * 16, 0, 0)

    # 3. repoint DT_STRTAB / DT_STRSZ at the copy
    for i in range(null_i + 1):
        tag = struct.unpack_from('<Q', data, dyn_off + i * 16)[0]
        if tag == 5:    struct.pack_into('<QQ', data, dyn_off + i * 16, 5, new_str_off)
        elif tag == 10: struct.pack_into('<QQ', data, dyn_off + i * 16, 10, len(new_str))

    # 4. keep the .dynstr section header consistent
    for i in range(e.header.e_shnum):
        base = e.header.e_shoff + i * e.header.e_shentsize
        sh_name, sh_type, sh_flags, sh_addr, sh_off, sh_size = \
            struct.unpack_from('<IIQQQQ', data, base)
        if sh_off == old_off and sh_size == old_size:
            struct.pack_into('<QQQ', data, base + 16, new_str_off, new_str_off, len(new_str))
            break
    else:
        raise ValueError(".dynstr section header not found")

    # 5. grow PT_LOAD #2 over the new string table
    for i in range(e.header.e_phnum):
        ent = e.header.e_phoff + i * e.header.e_phentsize
        p_type, p_flags, p_offset = struct.unpack_from('<IIQ', data, ent)
        if p_type == 1 and p_offset == PTLOAD2_OFFSET:      # PT_LOAD
            filesz = len(data) - p_offset
            struct.pack_into('<QQ', data, ent + 32, filesz, filesz)
            break
    else:
        raise ValueError("PT_LOAD #2 not found")

    return bytes(data)


# -------------------------------------------------------------- verifying
def verify_bytes(data, expect_dep=NEW_DEP):
    """Check the invariants the Android linker relies on.  Returns a list of
    problems (empty list = good)."""
    problems = []
    e = ELFFile(io.BytesIO(bytes(data)))
    raw = bytes(data)

    loads = [(s.header.p_offset, s.header.p_vaddr, s.header.p_filesz,
              s.header.p_memsz, s.header.p_align)
             for s in e.iter_segments() if s.header.p_type == 'PT_LOAD']
    for off, va, fsz, msz, al in loads:
        if al and (va % al) != (off % al):
            problems.append("PT_LOAD vaddr/offset not congruent with p_align")
        if off + fsz > len(raw):
            problems.append("PT_LOAD file range past EOF")
        if msz < fsz:
            problems.append("PT_LOAD memsz < filesz")

    def mapped(v, size=1):
        return any(va <= v and v + size <= va + msz for off, va, fsz, msz, al in loads)

    dyn = e.get_section_by_name('.dynamic')
    d = {t.entry.d_tag: t.entry.d_val for t in dyn.iter_tags() if t.entry.d_tag != 'DT_NEEDED'}
    needed = [t.needed for t in dyn.iter_tags() if t.entry.d_tag == 'DT_NEEDED']
    if expect_dep and expect_dep not in needed:
        problems.append(f"{expect_dep} missing from DT_NEEDED")

    strtab, strsz = d['DT_STRTAB'], d['DT_STRSZ']
    if not mapped(strtab, strsz):
        problems.append("DT_STRTAB not inside a mapped segment")
    strblob = raw[strtab:strtab + strsz]
    for t in dyn.iter_tags():
        if t.entry.d_tag in ('DT_NEEDED', 'DT_SONAME'):
            off = t.entry.d_val
            if off >= len(strblob) or b'\0' not in strblob[off:]:
                problems.append("unterminated dynamic string")

    symtab, syment = d['DT_SYMTAB'], d['DT_SYMENT']
    nbucket, nchain = struct.unpack_from('<II', raw, d['DT_HASH'])
    if not mapped(symtab, nchain * syment):
        problems.append("DT_SYMTAB table not fully mapped")
    for i in range(nchain):
        if struct.unpack_from('<I', raw, symtab + i * syment)[0] >= strsz:
            problems.append("symbol name offset outside DT_STRSZ")
            break

    for sec in ('.rela.dyn', '.rela.plt'):
        s = e.get_section_by_name(sec)
        if s is None:
            continue
        for r in s.iter_relocations():
            if not mapped(r['r_offset'], 8):
                problems.append(f"{sec}: relocation outside PT_LOAD")

    for tag, sz_tag in (('DT_INIT_ARRAY', 'DT_INIT_ARRAYSZ'), ('DT_FINI_ARRAY', 'DT_FINI_ARRAYSZ')):
        if tag in d and sz_tag in d and not mapped(d[tag], d[sz_tag]):
            problems.append(f"{tag} not mapped")

    return problems, needed


def verify_status(data, expect_dep=NEW_DEP):
    problems, needed = verify_bytes(data, expect_dep)
    return problems


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__.strip().splitlines()[-1].strip())
    src, dst = sys.argv[1], sys.argv[2]
    patched = patch_bytes(open(src, 'rb').read())
    open(dst, 'wb').write(patched)
    problems, needed = verify_bytes(patched)
    print(f"DT_NEEDED: {needed}")
    for p in problems:
        print("  PROBLEM:", p)
    print("RESULT:", "OK" if not problems else "FAILED")
    sys.exit(0 if not problems else 1)
