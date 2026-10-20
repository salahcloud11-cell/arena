#!/usr/bin/env python3
"""
Generate a Winlator (v11.x) touch-controls profile (.icp) for
"Prince of Persia: The Two Thrones" (PC, 2005) and render a preview image.

The .icp format and all binding names come from the Winlator source code
(github.com/brunodev85/winlator-app: inputcontrols/Binding.java,
ControlElement.java, ControlsProfile.java, InputControlsManager.java).

Key bindings follow the game's own PC manual ("Game-play Controls"):
  Left mouse  = dagger attack / Speed Kill finish
  Space       = action: jump, roll, climb up
  E           = trigger Speed Kill / secondary weapon / grab
  C           = climb down / pick up & throw weapon
  R           = rewind (hold) / slow motion (tap)
  Right mouse = special action: wall run, swing, push/pull, drink / block
  Q = landscape camera, F = first-person look, X = reset camera
  W A S D     = move, Mouse = camera, Esc = pause, Enter = select (menus)

Usage:  python3 make_controls.py  [--out-dir DIR]
Needs Pillow only for the preview image (pip install pillow).
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
import os
import sys

PROFILE_NAME = "Prince of Persia - The Two Thrones"
ICP_FILENAME = "00 ANDROID - touch controls.icp"
PREVIEW_FILENAME = "touch-controls-layout.png"

# Valid values copied from Winlator's Binding enum / ControlElement enums.
VALID_BINDINGS = set("""
NONE MOUSE_LEFT_BUTTON MOUSE_MIDDLE_BUTTON MOUSE_RIGHT_BUTTON MOUSE_MOVE_LEFT
MOUSE_MOVE_RIGHT MOUSE_MOVE_UP MOUSE_MOVE_DOWN MOUSE_SCROLL_UP MOUSE_SCROLL_DOWN
KEY_UP KEY_RIGHT KEY_DOWN KEY_LEFT KEY_ENTER KEY_ESC KEY_BKSP KEY_DEL KEY_INSERT
KEY_TAB KEY_SPACE KEY_CTRL_L KEY_CTRL_R KEY_SHIFT_L KEY_SHIFT_R KEY_ALT_L
KEY_ALT_R KEY_HOME KEY_PRTSCN KEY_PG_UP KEY_PG_DOWN KEY_END KEY_CAPS_LOCK
KEY_NUM_LOCK KEY_0 KEY_1 KEY_2 KEY_3 KEY_4 KEY_5 KEY_6 KEY_7 KEY_8 KEY_9 KEY_A
KEY_B KEY_C KEY_D KEY_E KEY_F KEY_G KEY_H KEY_I KEY_J KEY_K KEY_L KEY_M KEY_N
KEY_O KEY_P KEY_Q KEY_R KEY_S KEY_T KEY_U KEY_V KEY_W KEY_X KEY_Y KEY_Z
KEY_BRACKET_LEFT KEY_BRACKET_RIGHT KEY_BACKSLASH KEY_SLASH KEY_SEMICOLON
KEY_COMMA KEY_PERIOD KEY_APOSTROPHE KEY_KP_ADD KEY_MINUS KEY_F1 KEY_F2 KEY_F3
KEY_F4 KEY_F5 KEY_F6 KEY_F7 KEY_F8 KEY_F9 KEY_F10 KEY_F11 KEY_F12 KEY_KP_0
KEY_KP_1 KEY_KP_2 KEY_KP_3 KEY_KP_4 KEY_KP_5 KEY_KP_6 KEY_KP_7 KEY_KP_8 KEY_KP_9
GAMEPAD_BUTTON_A GAMEPAD_BUTTON_B GAMEPAD_BUTTON_X GAMEPAD_BUTTON_Y
GAMEPAD_BUTTON_L1 GAMEPAD_BUTTON_R1 GAMEPAD_BUTTON_SELECT GAMEPAD_BUTTON_START
GAMEPAD_BUTTON_L3 GAMEPAD_BUTTON_R3 GAMEPAD_BUTTON_L2 GAMEPAD_BUTTON_R2
GAMEPAD_LEFT_THUMB_UP GAMEPAD_LEFT_THUMB_RIGHT GAMEPAD_LEFT_THUMB_DOWN
GAMEPAD_LEFT_THUMB_LEFT GAMEPAD_RIGHT_THUMB_UP GAMEPAD_RIGHT_THUMB_RIGHT
GAMEPAD_RIGHT_THUMB_DOWN GAMEPAD_RIGHT_THUMB_LEFT GAMEPAD_DPAD_UP
GAMEPAD_DPAD_RIGHT GAMEPAD_DPAD_DOWN GAMEPAD_DPAD_LEFT KEY_VOL_UP KEY_VOL_DOWN
""".split())
VALID_TYPES = {"BUTTON", "D_PAD", "RANGE_BUTTON", "STICK", "TRACKPAD", "MIDI_KEY", "RADIAL_MENU"}
VALID_SHAPES = {"CIRCLE", "RECT", "ROUND_RECT", "SQUARE"}

# Element list: (type, shape, bindings, x, y, scale, label, caption-for-preview)
# x / y are the element centre as a fraction of screen width / height
# (this is exactly how Winlator stores them).
ELEMENTS = [
    # Left stick -> W/A/S/D  (Winlator order: up, right, down, left)
    ("STICK", "CIRCLE", ["KEY_W", "KEY_D", "KEY_S", "KEY_A"], 0.120, 0.700, 1.25, "", "MOVE"),
    # Right stick -> continuous mouse movement = camera (like a gamepad right stick)
    ("STICK", "CIRCLE", ["MOUSE_MOVE_UP", "MOUSE_MOVE_RIGHT", "MOUSE_MOVE_DOWN", "MOUSE_MOVE_LEFT"],
     0.660, 0.420, 1.25, "", "CAMERA"),
    # Action diamond (like the console layout)
    ("BUTTON", "CIRCLE", ["KEY_SPACE"], 0.875, 0.875, 1.15, "JUMP", "Space"),
    ("BUTTON", "CIRCLE", ["MOUSE_LEFT_BUTTON"], 0.810, 0.730, 1.05, "ATTACK", "Left click"),
    ("BUTTON", "CIRCLE", ["KEY_E"], 0.875, 0.585, 1.00, "E-KILL", "E"),
    ("BUTTON", "CIRCLE", ["KEY_C"], 0.940, 0.730, 1.00, "C-PICK", "C"),
    ("BUTTON", "CIRCLE", ["MOUSE_RIGHT_BUTTON"], 0.745, 0.880, 1.00, "WALL", "Right click"),
    # Time power (left thumb, above the move stick)
    ("BUTTON", "CIRCLE", ["KEY_R"], 0.070, 0.360, 1.10, "REWIND", "R"),
    # Camera keys + menu keys
    ("BUTTON", "RECT", ["KEY_Q"], 0.070, 0.080, 0.90, "Q-VIEW", ""),
    ("BUTTON", "RECT", ["KEY_F"], 0.170, 0.080, 0.90, "F-LOOK", ""),
    ("BUTTON", "RECT", ["KEY_X"], 0.830, 0.080, 0.90, "X-CAM", ""),
    ("BUTTON", "RECT", ["KEY_ESC"], 0.930, 0.080, 0.90, "ESC", ""),
    ("BUTTON", "ROUND_RECT", ["KEY_ENTER"], 0.500, 0.920, 0.85, "ENTER", ""),
]

# Camera stick speed: Winlator moves the mouse by offset * 10 * cursorSpeed px
# every 1/60 s. The game has a large mouse dead-zone, so use a bit more than 1.0.
CURSOR_SPEED = 1.3


def build_profile() -> dict:
    elements = []
    for etype, shape, bindings, x, y, scale, label, _caption in ELEMENTS:
        b = list(bindings) + ["NONE"] * (4 - len(bindings))
        elements.append({
            "type": etype,
            "shape": shape,
            "bindings": b,
            "scale": scale,
            "x": x,
            "y": y,
            "toggleSwitch": False,
            "text": label,
            "iconId": 0,
        })
    # "id" is required by Winlator's importer (it assigns a new id on import).
    return {"id": 1, "name": PROFILE_NAME, "cursorSpeed": CURSOR_SPEED, "elements": elements}


def validate(profile: dict) -> None:
    assert profile["name"] and isinstance(profile["id"], int)
    for e in profile["elements"]:
        assert e["type"] in VALID_TYPES, e
        assert e["shape"] in VALID_SHAPES, e
        assert len(e["bindings"]) == 4, e
        for b in e["bindings"]:
            assert b in VALID_BINDINGS, f"invalid binding {b}"
        assert 0.0 <= e["x"] <= 1.0 and 0.0 <= e["y"] <= 1.0, e


def element_geometry(e: dict, width: int, height: int):
    """Return ('circle', cx, cy, r) or ('rect', x0, y0, x1, y1) in pixels,
    using Winlator's own size rules (snappingSize = width / 100)."""
    s = width / 100.0
    cx, cy, k = e["x"] * width, e["y"] * height, e["scale"]
    if e["type"] in ("STICK", "TRACKPAD"):
        return ("circle", cx, cy, 6 * s * k)
    if e["type"] == "D_PAD":
        return ("rect", cx - 7 * s * k, cy - 7 * s * k, cx + 7 * s * k, cy + 7 * s * k)
    if e["shape"] == "CIRCLE":
        return ("circle", cx, cy, 3 * s * k)
    if e["shape"] in ("RECT", "ROUND_RECT"):
        return ("rect", cx - 4 * s * k, cy - 2 * s * k, cx + 4 * s * k, cy + 2 * s * k)
    return ("rect", cx - 2.5 * s * k, cy - 2.5 * s * k, cx + 2.5 * s * k, cy + 2.5 * s * k)


def _gap(a, b) -> float:
    """Smallest distance between two shapes (negative = overlap)."""
    def rect_of(g):
        if g[0] == "rect":
            return g[1:]
        _, cx, cy, r = g
        return (cx - r, cy - r, cx + r, cy + r)
    if a[0] == b[0] == "circle":
        return math.hypot(a[1] - b[1], a[2] - b[2]) - a[3] - b[3]
    if a[0] == "circle" or b[0] == "circle":
        c, r = (a, b) if a[0] == "circle" else (b, a)
        x0, y0, x1, y1 = r[1:]
        dx = max(x0 - c[1], 0, c[1] - x1)
        dy = max(y0 - c[2], 0, c[2] - y1)
        return math.hypot(dx, dy) - c[3]
    ax0, ay0, ax1, ay1 = rect_of(a)
    bx0, by0, bx1, by1 = rect_of(b)
    dx = max(bx0 - ax1, ax0 - bx1, 0)
    dy = max(by0 - ay1, ay0 - by1, 0)
    if dx == 0 and dy == 0:
        return -1.0
    return math.hypot(dx, dy)


def check_layout(profile: dict) -> list[str]:
    """Check every element fits on screen and nothing overlaps, for common
    phone aspect ratios (16:9 up to 21:9)."""
    problems = []
    for (w, h) in [(1920, 1080), (2340, 1080), (2400, 1080), (2520, 1080)]:
        geoms = [element_geometry(e, w, h) for e in profile["elements"]]
        for i, g in enumerate(geoms):
            if g[0] == "circle":
                _, cx, cy, r = g
                box = (cx - r, cy - r, cx + r, cy + r)
            else:
                box = g[1:]
            if box[0] < 0 or box[1] < 0 or box[2] > w or box[3] > h:
                problems.append(f"{w}x{h}: element {i} ({profile['elements'][i]['text'] or profile['elements'][i]['type']}) is off-screen")
        for i, j in itertools.combinations(range(len(geoms)), 2):
            gap = _gap(geoms[i], geoms[j])
            if gap < 0.004 * w:  # require a small visible gap
                ni = profile['elements'][i]['text'] or profile['elements'][i]['type']
                nj = profile['elements'][j]['text'] or profile['elements'][j]['type']
                problems.append(f"{w}x{h}: '{ni}' and '{nj}' overlap/touch (gap {gap:.1f}px)")
    return problems


def render_preview(profile: dict, path: str, width: int = 2400, height: int = 1080) -> None:
    from PIL import Image, ImageDraw, ImageFont

    img = Image.new("RGB", (width, height))
    px = img.load()
    # simple dusk-desert gradient background (no game art used)
    top, bottom = (24, 30, 56), (150, 96, 52)
    for y in range(height):
        t = y / (height - 1)
        col = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(width):
            px[x, y] = col
    draw = ImageDraw.Draw(img, "RGBA")

    def font(size, bold=True):
        for cand in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else
                     "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",):
            if os.path.exists(cand):
                return ImageFont.truetype(cand, size)
        return ImageFont.load_default(size=size)

    # faint "game screen" watermark
    wm = font(56, bold=False)
    msg = "(the game picture is behind the buttons)"
    tw = draw.textlength(msg, font=wm)
    draw.text(((width - tw) / 2, height * 0.17), msg, font=wm, fill=(140, 128, 140))

    stroke = max(3, int(width / 100 * 0.25))
    white = (255, 255, 255, 230)
    fill = (255, 255, 255, 38)
    cap_font = font(30, bold=True)
    for (etype, shape, bindings, x, y, scale, label, caption), e in zip(ELEMENTS, profile["elements"]):
        g = element_geometry(e, width, height)
        if g[0] == "circle":
            _, cx, cy, r = g
            draw.ellipse((cx - r, cy - r, cx + r, cy + r), outline=white, width=stroke, fill=fill)
            if etype == "STICK":
                ir = r * 0.45
                draw.ellipse((cx - ir, cy - ir, cx + ir, cy + ir), outline=white, width=stroke, fill=(255, 255, 255, 70))
            box_w = 2 * r
        else:
            x0, y0, x1, y1 = g[1:]
            if shape == "ROUND_RECT":
                draw.rounded_rectangle((x0, y0, x1, y1), radius=(y1 - y0) / 2, outline=white, width=stroke, fill=fill)
            else:
                draw.rectangle((x0, y0, x1, y1), outline=white, width=stroke, fill=fill)
            cx, cy, box_w = (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0
            r = (y1 - y0) / 2
        if label:
            # same idea as Winlator: shrink the text to fit the element width
            # Winlator: text size = min(fit-to-width, 2 * snappingSize * scale)
            size = int(2 * (width / 100.0) * scale)
            f = font(size)
            while draw.textlength(label, font=f) > box_w * 0.84 and size > 10:
                size -= 2
                f = font(size)
            tw = draw.textlength(label, font=f)
            draw.text((cx - tw / 2, cy - size * 0.58), label, font=f, fill=(255, 255, 255, 255))
        if caption:
            cap = caption if etype == "STICK" else f"({caption})"
            tw = draw.textlength(cap, font=cap_font)
            ty = cy + r + 8
            if ty + 34 > height:
                ty = cy - r - 42
            draw.text((cx - tw / 2, ty), cap, font=cap_font, fill=(255, 214, 102, 255))
    img.save(path, optimize=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--out-dir", default=os.path.normpath(os.path.join(here, "..")))
    args = ap.parse_args()

    profile = build_profile()
    validate(profile)
    problems = check_layout(profile)
    if problems:
        print("LAYOUT PROBLEMS:\n  " + "\n  ".join(problems))
        return 1

    win_dir = os.path.join(args.out_dir, "winlator")
    doc_dir = os.path.join(args.out_dir, "docs")
    os.makedirs(win_dir, exist_ok=True)
    os.makedirs(doc_dir, exist_ok=True)

    icp_path = os.path.join(win_dir, ICP_FILENAME)
    with open(icp_path, "w", encoding="utf-8") as fh:
        json.dump(profile, fh, indent=2)
        fh.write("\n")
    print("wrote", icp_path, f"({len(profile['elements'])} elements)")

    try:
        render_preview(profile, os.path.join(doc_dir, PREVIEW_FILENAME))
        print("wrote", os.path.join(doc_dir, PREVIEW_FILENAME))
    except ImportError:
        print("Pillow not installed - skipped preview image")
    return 0


if __name__ == "__main__":
    sys.exit(main())
