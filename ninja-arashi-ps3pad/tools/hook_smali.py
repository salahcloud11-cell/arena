#!/usr/bin/env python3
"""
hook_smali.py -- inject the PS3 mapper into the game's own activity.

This is the same mechanism as the working Granny / CoD-Zombies mods: instead of
replacing the activity or relying on Window.Callback (Unity bypasses both), the
dispatch methods inside the activity's smali are edited so they call the mapper
first and return early when it handled the event.

Hooked methods (injected only if missing):
    dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z
    dispatchTouchEvent(Landroid/view/MotionEvent;)Z

The injected calls are:
    Lcom/ps3mod/PS3Mapper;->onKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z
    Lcom/ps3mod/PS3Mapper;->onMotion(Landroid/app/Activity;Landroid/view/MotionEvent;)Z
    Lcom/ps3mod/PS3Mapper;->onTouch(Landroid/app/Activity;Landroid/view/MotionEvent;)Z
"""
import os
import re

HOOKS = [
    ("dispatchKeyEvent", "Landroid/view/KeyEvent;", "onKey", "ps3_key"),
    ("dispatchGenericMotionEvent", "Landroid/view/MotionEvent;", "onMotion", "ps3_motion"),
    ("dispatchTouchEvent", "Landroid/view/MotionEvent;", "onTouch", "ps3_touch"),
]

INSTRUCTION_RE = re.compile(r"^[ ]{4}[\w/$\-]+")


def find_activity_smali(tree):
    """Return (path, descriptor) of the launcher activity's smali file."""
    manifest = open(os.path.join(tree, "AndroidManifest.xml"), encoding="utf-8").read()

    names = []
    # the launcher activity is the one whose intent-filter has MAIN+LAUNCHER
    for m in re.finditer(r"<activity\b[^>]*?android:name=\"([^\"]+)\"(.*?)</activity>",
                         manifest, re.S):
        name, body = m.group(1), m.group(2)
        if "android.intent.action.MAIN" in body and "android.intent.category.LAUNCHER" in body:
            names.insert(0, name)
        else:
            names.append(name)
    # aliases can also carry the launcher filter; prefer the real activity
    alias = re.search(r"<activity-alias\b[^>]*?android:targetActivity=\"([^\"]+)\"", manifest)
    if alias and alias.group(1) not in names:
        names.append(alias.group(1))

    roots = [os.path.join(tree, d) for d in os.listdir(tree)
             if d == "smali" or d.startswith("smali_classes")]
    for name in names:
        rel = name.replace(".", "/") + ".smali"
        for root in roots:
            path = os.path.join(root, rel)
            if os.path.exists(path):
                return path, "L" + name.replace(".", "/") + ";", name
    raise SystemExit("could not find the launcher activity's smali file (looked for %s)" % names[:5])


def method_bounds(lines, method_name, signature):
    """Locate a method and return (start_index, end_index, locals_index)."""
    head = ".method"
    for i, line in enumerate(lines):
        if line.startswith(head) and (" " + method_name + "(") in line:
            end = i
            while end < len(lines) and not lines[end].startswith(".end method"):
                end += 1
            locals_idx = None
            for j in range(i, end):
                st = lines[j].strip()
                if st.startswith(".locals") or st.startswith(".registers"):
                    locals_idx = j
                    break
            return i, end, locals_idx
    return None


def bump_registers(lines, locals_idx):
    """Add one scratch register and return its smali name."""
    st = lines[locals_idx].strip()
    if st.startswith(".locals"):
        n = int(st.split()[1])
        lines[locals_idx] = "    .locals %d" % (n + 1)
        return "v%d" % n
    # .registers R with 2 params (this + the event): locals are v0..v(R-3) and
    # the params are the last two registers.  After bumping to R+1 the new local
    # is v(R-2) -- NOT v(R-1), which is p0.
    n = int(st.split()[1])
    lines[locals_idx] = "    .registers %d" % (n + 1)
    return "v%d" % (n - 2)


def inject(lines, method_name, param_desc, hook_name, label, class_desc, super_desc):
    found = method_bounds(lines, method_name, param_desc)
    signature = "(%s)Z" % param_desc
    call = ("    invoke-static {p0, p1}, Lcom/ps3mod/PS3Mapper;->%s"
            "(Landroid/app/Activity;%s)Z" % (hook_name, param_desc))

    if found is None:
        body = [
            "",
            ".method public %s%s" % (method_name, signature),
            "    .locals 2",
            "",
            "    const/4 v0, 0x0",
            "",
            "    :try_start",
            "",
            call,
            "",
            "    move-result v0",
            "",
            "    :try_end",
            "    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :ps3_err",
            "",
            "    if-eqz v0, :cont",
            "",
            "    const/4 v0, 0x1",
            "",
            "    return v0",
            "",
            "    :ps3_err",
            "    const/4 v0, 0x0",
            "",
            "    :cont",
            "",
            "    invoke-super {p0, p1}, %s->%s%s" % (super_desc, method_name, signature),
            "",
            "    move-result v0",
            "",
            "    return v0",
            ".end method",
        ]
        # insert before the last .end method of the class so .field/.annotation
        # placement rules stay intact
        idx = len(lines) - 1
        while idx > 0 and lines[idx].strip() != "":
            idx -= 1
        lines[idx:idx] = body
        return "added"

    start, end, locals_idx = found
    if locals_idx is None:
        return "skipped (no .locals/.registers)"
    if any("Lcom/ps3mod/PS3Mapper;->" + hook_name in l for l in lines[start:end]):
        return "already hooked"

    reg = bump_registers(lines, locals_idx)

    block = [
        "",
        "    const/4 %s, 0x0" % reg,
        "",
        "    :%s_try_start" % label,
        "",
        call,
        "",
        "    move-result %s" % reg,
        "",
        "    :%s_try_end" % label,
        "    .catch Ljava/lang/Throwable; {:%s_try_start .. :%s_try_end} :%s_err"
        % (label, label, label),
        "",
        "    if-eqz %s, :%s_cont" % (reg, label),
        "",
        "    const/4 %s, 0x1" % reg,
        "",
        "    return %s" % reg,
        "",
        "    :%s_err" % label,
        "    const/4 %s, 0x0" % reg,
        "",
        "    :%s_cont" % label,
    ]
    # insert before the first real instruction of the method
    insert_at = None
    for j in range(locals_idx + 1, end):
        st = lines[j].strip()
        if not st or st.startswith(".") or st.startswith(":") or st.startswith("#"):
            continue
        if INSTRUCTION_RE.match(lines[j]):
            insert_at = j
            break
    if insert_at is None:
        return "skipped (no instruction found)"
    lines[insert_at:insert_at] = block
    return "hooked"


def hook(tree, super_desc_override=None):
    path, class_desc, name = find_activity_smali(tree)

    # keep the pristine file: the build step restores it if the rebuild fails
    try:
        import shutil
        shutil.copyfile(path, path + ".orig")
    except Exception:                            # noqa: BLE001
        pass
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().split("\n")

    super_desc = super_desc_override
    if not super_desc:
        for line in lines:
            if line.startswith(".super "):
                super_desc = line.split()[1]
                break

    results = []
    for method_name, param_desc, hook_name, label in HOOKS:
        results.append((method_name, inject(lines, method_name, param_desc, hook_name,
                                            label, class_desc, super_desc)))

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    return name, class_desc, os.path.relpath(path, tree), super_desc, results


if __name__ == "__main__":
    import sys
    name, desc, rel, sup, res = hook(sys.argv[1])
    print("activity:", name, desc, "super:", sup)
    print("file:", rel)
    for method, status in res:
        print("  %-28s %s" % (method, status))
