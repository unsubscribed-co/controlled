# Draws Controlled for PC's tray icons from the app's own logo (docs/brand/icon-512.png), so it's recognizable next to
# the clock. States: "on" is the logo as is (ember lit: a phone has control); "idle" grays the ember (off); "paused"
# adds a small yellow pause badge. The logo is a dark square, so one set reads on light and dark taskbars alike.
# Run: python pc/assets/tray.py
from PIL import Image, ImageDraw
import os

here = os.path.dirname(os.path.abspath(__file__))
logo = Image.open(os.path.join(here, "..", "..", "docs", "brand", "icon-512.png")).convert("RGBA").resize((256, 256), Image.LANCZOS)


def idle(im):
    # Ember pixels (strongly orange) turn neutral gray: the light is off.
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if r > 150 and r - b > 80:
                v = (r + g + b) // 3
                px[x, y] = (v, v, v, a)
    return im


def paused(im):
    im = idle(im)  # gray first, so the yellow badge keeps its color
    d = ImageDraw.Draw(im)
    c, r = (196, 196), 58
    d.ellipse((c[0] - r - 10, c[1] - r - 10, c[0] + r + 10, c[1] + r + 10), fill=(0, 0, 0, 0))
    d.ellipse((c[0] - r, c[1] - r, c[0] + r, c[1] + r), fill=(234, 179, 8, 255))
    for x in (c[0] - 24, c[0] + 8):
        d.rounded_rectangle((x, c[1] - 30, x + 16, c[1] + 30), radius=4, fill=(30, 30, 30, 255))
    return im


for state, make in (("on", lambda im: im), ("idle", idle), ("paused", paused)):
    im = make(logo.copy()).resize((32, 32), Image.LANCZOS)
    for theme in ("light", "dark"):
        im.save(os.path.join(here, f"tray-{theme}-{state}.png"))
print("ok")
