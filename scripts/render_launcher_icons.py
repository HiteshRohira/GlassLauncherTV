#!/usr/bin/env python3
# Render Mirage's raster icon assets.
#
# The adaptive icon (res/drawable/ic_launcher_background.xml +
# ic_launcher_foreground.xml) is what every device on minSdk 26 shows.
# This script draws the same composition with numpy for the legacy
# square PNGs (res/mipmap-*/ic_launcher.png, 48-192 px), and writes the
# Android TV banner (res/drawable/banner.xml) as a vector that reuses the
# foreground artwork plus the two-line "Glass Launcher" wordmark as glyph
# outlines.
#
# Geometry is in the 108-unit adaptive-icon canvas. A frosted 5:3 glass
# card sits over a sea-glass orb; the part of the orb behind the card is
# shown blurred. The vector drawables fake that blur with a radial
# gradient clipped to the card, and so does this script, so both match.
#
# Run from the repo root:
#   python3 scripts/render_launcher_icons.py [--font PATH] [--preview PATH]
#
# --font points at the variable Outfit TTF (OFL) used for the banner
# wordmark; it needs fontTools. Without --font the banner is left as is.

from __future__ import annotations

import argparse
import os

import numpy as np
import xml.etree.ElementTree as ET

from PIL import Image

RES = os.path.join("glasslauncher", "app", "src", "main", "res")

DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

BG_A = (0x12, 0x3A, 0x4C)
BG_B = (0x07, 0x12, 0x1C)

CARD = (28.0, 49.5, 73.0, 76.5)
CARD_R = 7.0
ORB_C = (65.0, 46.5)
ORB_R = 15.0
ORB_A = (0x8A, 0xF5, 0xE4)
ORB_B = (0x1F, 0xB6, 0xC9)
SOFT_R = 25.0
SOFT_STOPS = [(0.0, (0xD2, 0xFF, 0xF8), 0.95), (0.40, (0x7C, 0xEB, 0xDF), 0.62), (1.0, (0x2C, 0xC7, 0xC9), 0.0)]
SHADOW_OFFSET = (1.0, 2.5)
SHADOW_ALPHA = 0.30
FROST = (0xE6, 0xFF, 0xFC)
FROST_TOP = 0.16
FROST_BOTTOM = 0.05
RIM_W = 0.9
RIM_TOP = 0.95
RIM_BOTTOM = 0.10
SHEEN_FROM = (28.0, 49.5)
SHEEN_TO = (44.0, 70.0)
SHEEN_ALPHA = 0.20


def grid(x0, y0, x1, y1, w, h):
    xs = x0 + (np.arange(w) + 0.5) * (x1 - x0) / w
    ys = y0 + (np.arange(h) + 0.5) * (y1 - y0) / h
    return np.meshgrid(xs, ys), (x1 - x0) / w


def sdf_round_rect(X, Y, rect, r):
    x0, y0, x1, y1 = rect
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = (x1 - x0) / 2 - r, (y1 - y0) / 2 - r
    qx, qy = np.abs(X - cx) - hx, np.abs(Y - cy) - hy
    return np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r


def sdf_circle(X, Y, c, r):
    return np.hypot(X - c[0], Y - c[1]) - r


def cover(sdf, px):
    return np.clip(0.5 - sdf / px, 0.0, 1.0)


def linear_t(X, Y, p0, p1):
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    return np.clip(((X - p0[0]) * dx + (Y - p0[1]) * dy) / (dx * dx + dy * dy), 0.0, 1.0)


def lerp(a, b, t):
    return a + (b - a) * t


def rgb(c):
    return np.array(c, dtype=np.float64) / 255.0


def over(dst, color, alpha):
    a = alpha[..., None]
    dst[..., :3] = color * a + dst[..., :3] * (1 - a)
    dst[..., 3:] = a + dst[..., 3:] * (1 - a)


def gradient_rgb(t, a, b):
    return lerp(rgb(a), rgb(b), t[..., None])


def background(X, Y):
    return gradient_rgb(linear_t(X, Y, (0, 0), (108, 108)), BG_A, BG_B)


def compose(window, w, h, with_background=True):
    (X, Y), px = grid(*window, w, h)
    img = np.zeros((h, w, 4))
    if with_background:
        img[..., :3] = background(X, Y)
        img[..., 3] = 1.0

    card = cover(sdf_round_rect(X, Y, CARD, CARD_R), px)
    sx, sy = SHADOW_OFFSET
    shadow_rect = (CARD[0] + sx, CARD[1] + sy, CARD[2] + sx, CARD[3] + sy)
    over(img, np.zeros(3), cover(sdf_round_rect(X, Y, shadow_rect, CARD_R), px) * SHADOW_ALPHA)

    orb_t = linear_t(X, Y, (ORB_C[0] - 13, ORB_C[1] - 13.5), (ORB_C[0] + 13, ORB_C[1] + 13.5))
    over(img, gradient_rgb(orb_t, ORB_A, ORB_B), cover(sdf_circle(X, Y, ORB_C, ORB_R), px))

    over(img, background(X, Y), card)
    rt = np.clip(np.hypot(X - ORB_C[0], Y - ORB_C[1]) / SOFT_R, 0, 1)
    soft_rgb = np.zeros(X.shape + (3,))
    soft_a = np.zeros(X.shape)
    for (t0, c0, a0), (t1, c1, a1) in zip(SOFT_STOPS, SOFT_STOPS[1:]):
        m = (rt >= t0) & (rt <= t1)
        u = (rt[m] - t0) / (t1 - t0)
        soft_rgb[m] = lerp(rgb(c0), rgb(c1), u[:, None])
        soft_a[m] = lerp(a0, a1, u)
    over(img, soft_rgb, soft_a * card)

    ft = linear_t(X, Y, (0, CARD[1]), (0, CARD[3]))
    over(img, rgb(FROST), lerp(FROST_TOP, FROST_BOTTOM, ft) * card)
    st = linear_t(X, Y, SHEEN_FROM, SHEEN_TO)
    over(img, np.ones(3), (1 - st) * SHEEN_ALPHA * card)

    rim = cover(np.abs(sdf_round_rect(X, Y, CARD, CARD_R)) - RIM_W / 2, px)
    rim_t = linear_t(X, Y, CARD[:2], CARD[2:])
    over(img, np.ones(3), rim * lerp(RIM_TOP, RIM_BOTTOM, rim_t))

    return img


def to_image(img):
    a = img[..., 3:]
    out = np.concatenate([img[..., :3], a], axis=-1)
    return Image.fromarray(np.round(np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA")


def legacy_icon(size):
    img = compose((18, 18, 90, 90), size, size)
    (X, Y), px = grid(0, 0, 1, 1, size, size)
    mask = cover(sdf_round_rect(X, Y, (0, 0, 1, 1), 0.22), px)
    img[..., 3] *= mask
    return to_image(img)



ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
BANNER_W, BANNER_H = 320.0, 180.0
GLYPH_BOX = (23.0, 28.0, 81.0, 80.0)
WORD_LINES = ("Glass", "Launcher")
WORD_SIZE = 40.0
WORD_LEADING = 1.08
WORD_WEIGHT = 300
WORD_GAP = 12.0
GLYPH_SHARE = 0.46


def wordmark_path(font_path):
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer

    font = instancer.instantiateVariableFont(TTFont(font_path), {"wght": WORD_WEIGHT})
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    k = WORD_SIZE / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    bounds = BoundsPen(glyphs)
    for row, line in enumerate(WORD_LINES):
        x, y = 0.0, row * WORD_SIZE * WORD_LEADING
        for ch in line:
            name = cmap[ord(ch)]
            glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, x, y)))
            glyphs[name].draw(TransformPen(bounds, (k, 0, 0, -k, x, y)))
            x += glyphs[name].width * k
    return pen.getCommands(), bounds.bounds


def write_banner(font_path):
    ET.register_namespace("android", ANDROID)
    ET.register_namespace("aapt", AAPT)
    a = lambda n: f"{{{ANDROID}}}{n}"
    fg = ET.parse(os.path.join(RES, "drawable", "ic_launcher_foreground.xml")).getroot()
    bg = ET.parse(os.path.join(RES, "drawable", "ic_launcher_background.xml")).getroot()

    word, (wx0, wy0, wx1, wy1) = wordmark_path(font_path)
    gx0, gy0, gx1, gy1 = GLYPH_BOX
    glyph_h = BANNER_H * GLYPH_SHARE
    scale = glyph_h / (gy1 - gy0)
    glyph_w = (gx1 - gx0) * scale
    total = glyph_w + WORD_GAP + (wx1 - wx0)
    left = (BANNER_W - total) / 2

    root = ET.Element("vector", {a("width"): "320dp", a("height"): "180dp",
                                 a("viewportWidth"): "320", a("viewportHeight"): "180"})
    bg_group = ET.SubElement(root, "group", {a("scaleX"): f"{BANNER_W / 108:.5f}", a("scaleY"): f"{BANNER_H / 108:.5f}"})
    bg_group.extend(list(bg))
    icon = ET.SubElement(root, "group", {
        a("scaleX"): f"{scale:.5f}", a("scaleY"): f"{scale:.5f}",
        a("translateX"): f"{left - gx0 * scale:.3f}",
        a("translateY"): f"{(BANNER_H - glyph_h) / 2 - gy0 * scale:.3f}"})
    icon.extend(list(fg))
    word_group = ET.SubElement(root, "group", {
        a("translateX"): f"{left + glyph_w + WORD_GAP - wx0:.3f}",
        a("translateY"): f"{(BANNER_H - (wy1 - wy0)) / 2 - wy0:.3f}"})
    ET.SubElement(word_group, "path", {a("fillColor"): "#F2FFFFFF", a("pathData"): word})
    ET.indent(root, space="    ")
    out = os.path.join(RES, "drawable", "banner.xml")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
        fh.write("<!-- Generated by scripts/render_launcher_icons.py from the Outfit font. -->\n")
        fh.write(ET.tostring(root, encoding="unicode"))
        fh.write("\n")
    print("wrote", out)


def preview_sheet(path):
    big = 360
    full = to_image(compose((18, 18, 90, 90), big, big))
    (X, Y), px = grid(18, 18, 90, 90, big, big)
    circle = cover(sdf_circle(X, Y, (54, 54), 36), px)
    squircle = cover(sdf_round_rect(X, Y, (18, 18, 90, 90), 20), px)
    sheet = Image.new("RGBA", (big * 3 + 80, big + 40), (32, 34, 38, 255))
    for i, m in enumerate((squircle, circle)):
        t = full.copy()
        t.putalpha(Image.fromarray(np.round(m * 255).astype(np.uint8)))
        sheet.alpha_composite(t, (20 + i * (big + 20), 20))
    sheet.alpha_composite(legacy_icon(96).resize((big, big), Image.NEAREST), (20 + 2 * (big + 20), 20))
    sheet.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font")
    ap.add_argument("--preview")
    args = ap.parse_args()
    if args.preview:
        preview_sheet(args.preview)
        return
    for folder, size in DENSITIES:
        out = os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png")
        legacy_icon(size).save(out, optimize=True)
        print("wrote", out)
    if args.font:
        write_banner(args.font)


if __name__ == "__main__":
    main()
