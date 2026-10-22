"""Store art from the placeholder launcher icon (a d-pad ring and the ember standby dot on near-black).
shortcut: placeholder art; redo both from the house-style "C" icon (stacksplit/visual-style) once Juan generates it.
Usage: python docs/brand/art.py  -> docs/brand/icon-512.png, docs/brand/feature-1024x500.png, site/controlled/icon.png"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BG, RING, EMBER = (13, 14, 17), (242, 238, 233), (255, 106, 61)
here = Path(__file__).parent
S = 4  # draw at 4x, then downsample: smooth edges


def mark(draw, cx, cy, unit):
    """The launcher foreground on a 108-unit grid: ring r=22 (stroke 7), dot r=8."""
    r, w, d = 22 * unit, 7 * unit, 8 * unit
    draw.ellipse([cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2], outline=RING, width=round(w))
    draw.ellipse([cx - d, cy - d, cx + d, cy + d], fill=EMBER)


icon = Image.new("RGB", (512 * S, 512 * S), BG)
mark(ImageDraw.Draw(icon), 256 * S, 256 * S, 512 * S / 108)
icon = icon.resize((512, 512), Image.LANCZOS)
icon.save(here / "icon-512.png")
icon.resize((192, 192), Image.LANCZOS).save(here.parent.parent / "site/controlled/icon.png")

feature = Image.new("RGB", (1024 * S, 500 * S), BG)
draw = ImageDraw.Draw(feature)
mark(draw, 250 * S, 250 * S, 300 * S / 108)
font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 96 * S)
draw.text((450 * S, 250 * S), "Controlled", font=font, fill=RING, anchor="lm")
feature.resize((1024, 500), Image.LANCZOS).save(here / "feature-1024x500.png")
print("ok")
