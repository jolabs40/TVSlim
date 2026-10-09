"""Draws the TV Slim logo and generates every file that carries it.

A TV without antenna on a central stand, with six apps on screen. The two extra ones, red and
yellow, disintegrate into pixels, leaving TV Slim's mint green and blue. 108 grid, like Android
vectors. Below 24 px a simplified version takes over: four tiles and a thick frame, which .ico
pixels can still render.

    pip install pillow fonttools uharfbuzz
    python tools/logo.py

The name font (Outfit, OFL) is only needed for the TV banner. It is downloaded from the Google Fonts
repository and checked against its SHA-256, or read from the path given with --font.

Every file written below is generated: hand edits are lost on the next run.
"""

from __future__ import annotations

import argparse
import hashlib
import tempfile
import urllib.request
from dataclasses import dataclass, field, replace
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

BACKGROUND = "#101418"
SCREEN = "#06090C"
FRAME = "#8AB4F8"
MINT = "#7FD1AE"
BLUE = "#5E97F6"
RED = "#EA4335"
YELLOW = "#FBBC04"
NAME_TEXT = "#EEF3F0"
SILHOUETTE = "#FFFFFF"

FONT_URL = "https://github.com/google/fonts/raw/main/ofl/outfit/Outfit%5Bwght%5D.ttf"
FONT_SHA256 = "fc7287273e66929776e2ba54f144fe699080bec29f61bf649d70d871468aeade"
FONT_WEIGHT = 600

ICO_SIZES = [256, 128, 64, 48, 40, 32, 24, 20, 16]
SUPERSAMPLING = 8


@dataclass(frozen=True)
class Shape:
    """A rounded rectangle or a polygon, in viewport coordinates."""

    kind: str
    x: float = 0
    y: float = 0
    w: float = 0
    h: float = 0
    r: float = 0
    points: tuple = ()
    fill: str | None = None
    opacity: float = 1.0
    stroke: str | None = None
    stroke_width: float = 0.0
    path_data: str = ""


def rect(x, y, w, h, r, fill=None, opacity=1.0, stroke=None, stroke_width=0.0) -> Shape:
    return Shape("rect", x, y, w, h, r, fill=fill, opacity=opacity, stroke=stroke, stroke_width=stroke_width)


def poly(points, fill) -> Shape:
    return Shape("poly", points=tuple(points), fill=fill)


@dataclass(frozen=True)
class Geometry:
    frame: tuple            # x, y, w, h, radius, stroke width
    tiles_x: tuple
    tiles_y: tuple
    tile: tuple             # w, h, radius
    tile_colors: tuple      # color of each tile, row by row
    neck: tuple
    base: tuple             # x, y, w, h, radius
    pixels: bool            # pixel dust only fits at large sizes
    kept_fraction: float


LARGE = Geometry(
    frame=(17, 22, 74, 50, 7, 6),
    tiles_x=(26, 46 + 1 / 3, 66 + 2 / 3),
    tiles_y=(31, 49.5),
    tile=(15 + 1 / 3, 13.5, 2.5),
    tile_colors=(("mint", "red", "blue"), ("blue", "mint", "yellow")),
    neck=((49, 75), (59, 75), (61, 81), (47, 81)),
    base=(34, 81, 40, 6, 3),
    pixels=True,
    kept_fraction=0.42,
)

SMALL = Geometry(
    frame=(14, 16, 80, 60, 9, 10),
    tiles_x=(25, 57),
    tiles_y=(27, 49),
    tile=(26, 16, 3),
    tile_colors=(("mint", "red"), ("yellow", "blue")),
    neck=((48, 81), (60, 81), (61, 86), (47, 86)),
    base=(30, 86, 48, 7, 3.5),
    pixels=False,
    kept_fraction=0.45,
)

# What is left of a disintegrating tile: one column of pixels per slice, sparser and more
# transparent each time, then three crumbs drifting off to the right.
KEPT_PIXELS = ((1, 1, 0, 1), (0, 1, 1, 0), (1, 0, 0, 0))
PIXEL_OPACITY = (0.9, 0.6, 0.35)
CRUMBS = ((3.05, 0.3, 0.45, 0.3), (3.4, 1.6, 0.38, 0.2), (3.2, 2.8, 0.32, 0.15))


def palette(mono: bool) -> dict:
    if mono:
        return dict(screen=None, frame=SILHOUETTE, mint=SILHOUETTE, blue=SILHOUETTE, red=SILHOUETTE, yellow=SILHOUETTE)
    return dict(screen=SCREEN, frame=FRAME, mint=MINT, blue=BLUE, red=RED, yellow=YELLOW)


def mark(small: bool = False, mono: bool = False) -> list[Shape]:
    """The TV alone, without background, on the 108 grid."""
    g = SMALL if small else LARGE
    p = palette(mono)
    x, y, w, h, r, sw = g.frame
    shapes = [
        poly(g.neck, p["frame"]),
        rect(*g.base, fill=p["frame"]),
        rect(x, y, w, h, r, fill=p["screen"], stroke=p["frame"], stroke_width=sw),
    ]
    tw, th, tr = g.tile
    for row, colors in enumerate(g.tile_colors):
        for col, color_name in enumerate(colors):
            tx, ty, color = g.tiles_x[col], g.tiles_y[row], p[color_name]
            if color_name not in ("red", "yellow"):
                shapes.append(rect(tx, ty, tw, th, tr, color))
                continue
            x0 = tx + tw * g.kept_fraction
            shapes.append(rect(tx, ty, tw * g.kept_fraction, th, tr, color))
            shapes.append(rect(x0 - tr, ty, tr, th, 0, color))
            if not g.pixels:
                continue
            s = th / 4
            for c, column in enumerate(KEPT_PIXELS):
                for i, kept in enumerate(column):
                    if kept:
                        shapes.append(rect(x0 + c * s + 0.35, ty + i * s + 0.35, s - 0.7, s - 0.7, 0.4, color, PIXEL_OPACITY[c]))
            for dx, dy, k, o in CRUMBS:
                shapes.append(rect(x0 + dx * s, ty + dy * s, k * s, k * s, 0.3, color, o))
    return shapes


def transform(shapes: list[Shape], scale: float, dx: float, dy: float) -> list[Shape]:
    def pt(px, py):
        return (px * scale + dx, py * scale + dy)

    result = []
    for f in shapes:
        if f.kind == "poly":
            result.append(replace(f, points=tuple(pt(*p) for p in f.points)))
        elif f.kind == "rect":
            x, y = pt(f.x, f.y)
            result.append(replace(f, x=x, y=y, w=f.w * scale, h=f.h * scale, r=f.r * scale, stroke_width=f.stroke_width * scale))
        else:
            result.append(f)
    return result


def full_icon(small: bool = False) -> list[Shape]:
    """The rounded square used for Windows, GitHub and link previews."""
    return [rect(0, 0, 108, 108, 24, BACKGROUND)] + mark(small)


def adaptive_foreground(mono: bool = False) -> list[Shape]:
    """An adaptive icon only shows the central 72 units under a round or rounded mask, so the TV
    fits in the 66-unit circle Android guarantees."""
    scale = 0.62
    return transform(mark(mono=mono), scale, 54 - 54 * scale, 54 - 53 * scale)


# --------------------------------------------------------------------------------------------
# The name, for the banner: drawn as paths, since an Android vector cannot render text.

@dataclass
class Wordmark:
    paths: list = field(default_factory=list)   # (pathData, color)


def outfit_font(path: str | None) -> Path:
    if path:
        return Path(path)
    cache = Path(tempfile.gettempdir()) / "tvslim-logo" / "Outfit-wght.ttf"
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(FONT_URL, timeout=30) as response:
            cache.write_bytes(response.read())
    digest = hashlib.sha256(cache.read_bytes()).hexdigest()
    if digest != FONT_SHA256:
        cache.unlink()
        raise SystemExit(f"Unexpected font ({digest}): nothing written.")
    return cache


def draw_name(font: Path, runs: list[tuple[str, str]], size: float, x: float, baseline: float) -> Wordmark:
    """Shapes the whole text, so the "TV" kerning applies, then renders each glyph in the color of
    the run it came from."""
    import uharfbuzz as hb
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont

    text = "".join(t for t, _ in runs)
    data = font.read_bytes()
    face = hb.Face(data)
    hb_font = hb.Font(face)
    hb_font.set_variations({"wght": FONT_WEIGHT})
    buffer = hb.Buffer()
    buffer.add_str(text)
    buffer.guess_segment_properties()
    hb.shape(hb_font, buffer)

    static_font = instantiateVariableFont(TTFont(font), {"wght": FONT_WEIGHT})
    glyphs = static_font.getGlyphSet()
    glyph_order = static_font.getGlyphOrder()
    scale = size / static_font["head"].unitsPerEm

    spans, start = [], 0
    for run, color in runs:
        spans.append((start, start + len(run), color))
        start += len(run)

    wordmark, advance = Wordmark(), 0.0
    for info, pos in zip(buffer.glyph_infos, buffer.glyph_positions):
        color = next(c for a, b, c in spans if a <= info.cluster < b)
        pen = SVGPathPen(glyphs, ntos=lambda n: f"{n:.2f}".rstrip("0").rstrip("."))
        gx = x + (advance + pos.x_offset) * scale
        glyphs[glyph_order[info.codepoint]].draw(TransformPen(pen, (scale, 0, 0, -scale, gx, baseline)))
        d = pen.getCommands()
        if d:
            wordmark.paths.append((d, color))
        advance += pos.x_advance
    return wordmark


# --------------------------------------------------------------------------------------------
# Output

def fmt(n: float) -> str:
    text = f"{n:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def rect_path(f: Shape) -> str:
    r = min(f.r, f.w / 2, f.h / 2)
    if r <= 0:
        return f"M{fmt(f.x)},{fmt(f.y)}h{fmt(f.w)}v{fmt(f.h)}h{fmt(-f.w)}z"
    a = f"{fmt(r)},{fmt(r)} 0 0 1"
    return (f"M{fmt(f.x + r)},{fmt(f.y)}h{fmt(f.w - 2 * r)}a{a} {fmt(r)},{fmt(r)}"
            f"v{fmt(f.h - 2 * r)}a{a} {fmt(-r)},{fmt(r)}h{fmt(-(f.w - 2 * r))}"
            f"a{a} {fmt(-r)},{fmt(-r)}v{fmt(-(f.h - 2 * r))}a{a} {fmt(r)},{fmt(-r)}z")


def shape_path(f: Shape) -> str:
    if f.kind == "rect":
        return rect_path(f)
    if f.kind == "poly":
        (x0, y0), *rest = f.points
        return f"M{fmt(x0)},{fmt(y0)}" + "".join(f"L{fmt(x)},{fmt(y)}" for x, y in rest) + "z"
    return f.path_data


def android_vector(shapes: list[Shape], width: int, height: int, viewport_w: int, viewport_h: int, note: str) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {note} Generated by tools/logo.py, do not edit by hand. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{width}dp"',
        f'    android:height="{height}dp"',
        f'    android:viewportWidth="{viewport_w}"',
        f'    android:viewportHeight="{viewport_h}">',
    ]
    for f in shapes:
        attrs = [f'android:pathData="{shape_path(f)}"']
        attrs.append(f'android:fillColor="{f.fill}"' if f.fill else 'android:fillColor="#00000000"')
        if f.opacity < 1:
            attrs.append(f'android:fillAlpha="{fmt(f.opacity)}"')
        if f.stroke:
            attrs.append(f'android:strokeColor="{f.stroke}"')
            attrs.append(f'android:strokeWidth="{fmt(f.stroke_width)}"')
        lines.append("    <path\n        " + "\n        ".join(attrs) + " />")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def svg(shapes: list[Shape], viewport_w: int, viewport_h: int, title: str) -> str:
    body = []
    for f in shapes:
        attrs = [f'd="{shape_path(f)}"', f'fill="{f.fill or "none"}"']
        if f.opacity < 1:
            attrs.append(f'fill-opacity="{fmt(f.opacity)}"')
        if f.stroke:
            attrs.append(f'stroke="{f.stroke}" stroke-width="{fmt(f.stroke_width)}"')
        body.append(f"  <path {' '.join(attrs)}/>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {viewport_w} {viewport_h}" role="img">\n'
            f"  <title>{title}</title>\n" + "\n".join(body) + "\n</svg>\n")


def adaptive_icon(note: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {note} Generated by tools/logo.py, do not edit by hand. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_logo_background" />\n'
        '    <foreground android:drawable="@drawable/ic_logo_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_logo_monochrome" />\n'
        "</adaptive-icon>\n"
    )


def rasterize(shapes: list[Shape], side: int, viewport: int = 108):
    """Rasterizes the shapes for the .ico: supersampled, then downscaled."""
    from PIL import Image, ImageDraw

    big = side * SUPERSAMPLING
    k = big / viewport
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))

    def rgba(color: str, opacity: float):
        c = color.lstrip("#")
        return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), round(255 * opacity))

    for f in shapes:
        layer = Image.new("RGBA", image.size, (0, 0, 0, 0)) if f.opacity < 1 else None
        draw = ImageDraw.Draw(layer or image)
        if f.kind == "poly":
            draw.polygon([(x * k, y * k) for x, y in f.points], fill=rgba(f.fill, f.opacity))
        else:
            # An SVG stroke is centered on the path; Pillow draws it inside the box.
            half = f.stroke_width / 2 if f.stroke else 0
            box = [(f.x - half) * k, (f.y - half) * k, (f.x + f.w + half) * k, (f.y + f.h + half) * k]
            radius = min(f.r + half, (f.w + 2 * half) / 2, (f.h + 2 * half) / 2) * k
            draw.rounded_rectangle(
                box,
                radius=radius,
                fill=rgba(f.fill, f.opacity) if f.fill else None,
                outline=rgba(f.stroke, 1) if f.stroke else None,
                width=round(f.stroke_width * k) if f.stroke else 0,
            )
        if layer is not None:
            image = Image.alpha_composite(image, layer)
    return image.resize((side, side), Image.LANCZOS)


# --------------------------------------------------------------------------------------------

def write_file(relative_path: str, content) -> None:
    target = ROOT / relative_path
    target.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, str):
        target.write_text(content, encoding="utf-8", newline="\n")
    else:
        target.write_bytes(content)
    print(f"  {relative_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    # --police is the former spelling, kept so existing commands still work.
    parser.add_argument("--font", "--police", dest="font", help="Outfit[wght].ttf, if it is already on disk")
    args = parser.parse_args()

    print("TV Slim logo:")
    write_file("docs/logo/tvslim.svg", svg(full_icon(), 108, 108, "TV Slim"))
    write_file("docs/logo/tvslim-petit.svg", svg(full_icon(small=True), 108, 108, "TV Slim, 24 px et moins"))

    background = android_vector([rect(0, 0, 108, 108, 0, BACKGROUND)], 108, 108, 108, 108, "Adaptive icon background.")
    foreground = android_vector(adaptive_foreground(), 108, 108, 108, 108,
                                "Adaptive icon foreground: the TV, inside the 66 dp safe circle.")
    monochrome = android_vector(adaptive_foreground(mono=True), 108, 108, 108, 108,
                                "Monochrome layer for Android 13 themed icons: only its alpha matters.")
    for module, icon in (("app-mobile", "ic_tvslim_remote"), ("app-tv", "ic_tvslim")):
        res = f"TVSlim/{module}/src/main/res/drawable"
        write_file(f"{res}/{icon}.xml", adaptive_icon("App icon, the same on the phone, the TV and Windows."))
        write_file(f"{res}/ic_logo_background.xml", background)
        write_file(f"{res}/ic_logo_foreground.xml", foreground)
        write_file(f"{res}/ic_logo_monochrome.xml", monochrome)

    # Inside the app, a plain vector: Compose cannot display an adaptive icon.
    write_file("TVSlim/app-mobile/src/main/res/drawable/ic_logo.xml",
               android_vector(full_icon(), 108, 108, 108, 108, "Logo for the phone app's top bar."))

    # The guardian's notification: Android only keeps the silhouette, hence the white version, drawn
    # small (four tiles) at 24 dp.
    silhouette = transform(mark(small=True, mono=True), 24 / 108, 0, 0)
    write_file("TVSlim/app-tv/src/main/res/drawable/ic_notification.xml",
               android_vector(silhouette, 24, 24, 24, 24, "Notification small icon: a white silhouette."))

    name = draw_name(outfit_font(args.font), [("TV", FRAME), (" Slim", NAME_TEXT)], 42, 152, 107)
    banner = [rect(0, 0, 320, 180, 0, BACKGROUND)] + transform(mark(), 1.22, 88 - 54 * 1.22, 92 - 53 * 1.22)
    banner += [Shape("path", fill=color, path_data=d) for d, color in name.paths]
    write_file("TVSlim/app-tv/src/main/res/drawable/tv_banner.xml",
               android_vector(banner, 320, 180, 320, 180, "Android TV banner, 320x180: logo and name."))

    write_file("TVSlim Windows/src/commonMain/composeResources/drawable/ic_tvslim.xml",
               android_vector(full_icon(), 108, 108, 108, 108, "TV Slim icon for Windows: window and About dialog."))

    images = [rasterize(full_icon(small=side <= 24), side) for side in ICO_SIZES]
    ico = ROOT / "TVSlim Windows/packaging/tvslim.ico"
    images[0].save(ico, format="ICO", sizes=[(s, s) for s in ICO_SIZES], append_images=images[1:])
    print(f"  TVSlim Windows/packaging/tvslim.ico — {', '.join(str(s) for s in ICO_SIZES)} px")
    images[0].save(ROOT / "TVSlim Windows/packaging/tvslim-256.png")
    print("  TVSlim Windows/packaging/tvslim-256.png")
    rasterize(full_icon(), 512).save(ROOT / "docs/logo/tvslim-512.png")
    print("  docs/logo/tvslim-512.png")
    # For the website: a phone home screen shortcut wants a full square, the system rounds it.
    rasterize([rect(0, 0, 108, 108, 0, BACKGROUND)] + mark(), 180).save(ROOT / "docs/logo/tvslim-carre-180.png")
    print("  docs/logo/tvslim-carre-180.png")


if __name__ == "__main__":
    main()
