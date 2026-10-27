"""Store screenshots from raw phone captures (adb exec-out screencap -p, POCO X7 Pro 1220x2712, dark theme).
Crops the status bar, blurs the TV's third-party app logos (Play rejects other brands' art), and pads to Play's 2:1 limit
with the app's own background. Usage: python docs/brand/screens.py <captures dir> -> docs/brand/screens/<name>.png"""
import sys
from pathlib import Path
from PIL import Image, ImageFilter

BG = (13, 14, 17)  # the app's near-black surface
STATUS = 110  # the status bar, in capture pixels
# The app dock's icon squares (Plex, Netflix, Prime Video, HBO Max) on the remote, from uiautomator bounds.
LOGOS = [(c - 100, 425, c + 100, 630) for c in (164, 387, 610, 834)]
WITH_DOCK = {"remote", "controllers"}

src, out = Path(sys.argv[1]), Path(__file__).parent / "screens"
out.mkdir(exist_ok=True)
for f in sorted(src.glob("*-e[ns].png")):
    im = Image.open(f).convert("RGB")
    if f.stem.rsplit("-", 1)[0] in WITH_DOCK:
        for box in LOGOS:
            im.paste(im.crop(box).filter(ImageFilter.GaussianBlur(28)), box)
    im = im.crop((0, STATUS, im.width, im.height))
    # Play accepts at most 2:1; widen with the background rather than cut the app's bottom row.
    w = max(im.width, (im.height + 1) // 2)
    canvas = Image.new("RGB", (w, im.height), BG)
    canvas.paste(im, ((w - im.width) // 2, 0))
    canvas.save(out / f.name)
    print(f.name, canvas.size)
