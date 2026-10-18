#!/usr/bin/env python3
"""
hook_smali.py -- inject the PS3 mapper into the game's own activity.

Same mechanism as the working Granny / CoD-Zombies mods: the activity's own smali
is edited so the mapper is called first.  The activity keeps its name and the
manifest is left alone (apart from the boot screen, done elsewhere).

Hooked methods
  dispatchKeyEvent(KeyEvent)Z          -> PS3Mapper.onKey(Activity,KeyEvent)Z
  dispatchGenericMotionEvent(Motion)Z  -> PS3Mapper.onMotion(Activity,Motion)Z
  dispatchTouchEvent(Motion)Z          -> PS3Mapper.onTouch(Activity,Motion)Z
  onCreate(Bundle)V                    -> PS3Mapper.onGameActivity(Activity)V
  onResume()V                          -> PS3Mapper.onGameActivity(Activity)V

The dispatch hooks return early when the mapper handled the event.  The lifecycle
hooks start the polling reader, which reads the pad straight from InputManager and
therefore works even if Unity swallows every event.

Every injected call is wrapped in try/catch(Throwable): if the mapper is missing
or throws, the game's own code runs unchanged and input never breaks.
"""
import os
import re
import shutil

INSTRUCTION_RE = re.compile(r"^[ ]{4}[\w/$\-]+")

# name, param descriptor, mapper method, label, returns-boolean?
HOOKS = [
    ("dispatchKeyEvent", "Landroid/view/KeyEvent;", "onKey", "ps3_key", True),
    ("dispatchGenericMotionEvent", "Landroid/view/MotionEvent;", "onMotion", "ps3_motion", True),
    ("dispatchTouchEvent", "Landroid/view/MotionEvent;", "onTouch", "ps3_touch", True),
    ("onCreate", "Landroid/os/Bundle;", "onGameActivity", "ps3_create", False),
    ("onResume", "", "onGameActivity", "ps3_resume", False),
]


def find_activity_smali(tree):
    manifest = open(os.path.join(tree, "AndroidManifest.xml"), encoding="utf-8").read()
    names = []
    for m in re.finditer(r"<activity\b[^>]*?android:name=\"([^\"]+)\"(.*?)</activity>",
                         manifest, re.S):
        name, body = m.group(1), m.group(2)
        if "android.intent.action.MAIN" in body and "android.intent.category.LAUNCHER" in body:
            names.insert(0, name)
        else:
            names.append(name)
    alias = re.search(r"<activity-alias\b[^>]*?android:targetActivity=\"([^\"]+)\"", manifest)
    if alias and alias.group(1) not in names:
        names.append(alias.group(1))

    roots = [os.path.join(tree, d) for d in sorted(os.listdir(tree))
             if d == "smali" or d.startswith("smali_classes")]
    for name in names:
        if name.startswith("com.ps3mod."):
            continue
        rel = name.replace(".", "/") + ".smali"
        for root in roots:
            path = os.path.join(root, rel)
            if os.path.exists(path):
                return path, "L" + name.replace(".", "/") + ";", name
    raise SystemExit("could not find the game activity's smali file (looked for %s)"
                     % names[:5])


def method_bounds(lines, method_name, signature):
    for i, line in enumerate(lines):
        if line.startswith(".method") and (" " + method_name + signature) in line:
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


def scratch_register(lines, locals_idx):
    st = lines[locals_idx].strip()
    if st.startswith(".locals"):
        n = int(st.split()[1])
        lines[locals_idx] = "    .locals %d" % (n + 1)
        return "v%d" % n
    # .registers R with 2 params (this + event): params are the last two, so
    # after bumping to R+1 the new local is v(R-2).
    n = int(st.split()[1])
    lines[locals_idx] = "    .registers %d" % (n + 1)
    return "v%d" % (n - 2)


def first_instruction(lines, start, end):
    for j in range(start, end):
        st = lines[j].strip()
        if not st or st.startswith(".") or st.startswith(":") or st.startswith("#"):
            continue
        if INSTRUCTION_RE.match(lines[j]):
            return j
    return None


def build_try_block(label, call, returns_bool, reg):
    """A guarded call. reg is unused for void hooks."""
    if returns_bool:
        return [
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
    return [
        "",
        "    :%s_try_start" % label,
        "",
        call,
        "",
        "    :%s_try_end" % label,
        "    .catch Ljava/lang/Throwable; {:%s_try_start .. :%s_try_end} :%s_err"
        % (label, label, label),
        "",
        "    :%s_err" % label,
    ]


def new_method_block(name, signature, call, returns_bool, super_desc, label):
    if not returns_bool:
        return [
            "",
            ".method public %s%s" % (name, signature),
            "    .locals 0",
            "",
            "    :try_start",
            "",
            call,
            "",
            "    :try_end",
            "    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :ps3_err",
            "",
            "    :ps3_err",
            "",
            "    invoke-super {p0%s}, %s->%s%s"
            % (", p1" if signature != "()V" else "", super_desc, name, signature),
            "",
            "    return-void",
            ".end method",
        ]
    return [
        "",
        ".method public %s%s" % (name, signature),
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
        "    invoke-super {p0, p1}, %s->%s%s" % (super_desc, name, signature),
        "",
        "    move-result v0",
        "",
        "    return v0",
        ".end method",
    ]


def inject(lines, name, param_desc, mapper_method, label, returns_bool, super_desc):
    signature = "(%s)%s" % (param_desc, "Z" if returns_bool else "V")
    if returns_bool:
        call = ("    invoke-static {p0, p1}, Lcom/ps3mod/PS3Mapper;->%s"
                "(Landroid/app/Activity;%s)Z" % (mapper_method, param_desc))
    elif param_desc:
        call = ("    invoke-static {p0}, Lcom/ps3mod/PS3Mapper;->%s"
                "(Landroid/app/Activity;)V" % mapper_method)
    else:
        call = ("    invoke-static {p0}, Lcom/ps3mod/PS3Mapper;->%s"
                "(Landroid/app/Activity;)V" % mapper_method)

    found = method_bounds(lines, name, signature)
    if found is None:
        body = new_method_block(name, signature, call, returns_bool, super_desc, label)
        idx = len(lines) - 1
        while idx > 0 and lines[idx].strip() != "":
            idx -= 1
        lines[idx:idx] = body
        return "added"

    start, end, locals_idx = found
    if any("Lcom/ps3mod/PS3Mapper;->" + mapper_method in l for l in lines[start:end]):
        return "already hooked"
    if locals_idx is None:
        return "skipped (no .locals/.registers)"

    reg = scratch_register(lines, locals_idx) if returns_bool else None
    block = build_try_block(label, call, returns_bool, reg)
    insert_at = first_instruction(lines, locals_idx + 1, end)
    if insert_at is None:
        return "skipped (no instruction found)"
    lines[insert_at:insert_at] = block
    return "hooked"


def hook(tree, super_desc_override=None):
    path, class_desc, name = find_activity_smali(tree)
    try:
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
    for m_name, param_desc, mapper_method, label, returns_bool in HOOKS:
        results.append((m_name, inject(lines, m_name, param_desc, mapper_method,
                                       label, returns_bool, super_desc)))

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
