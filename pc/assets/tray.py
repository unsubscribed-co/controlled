# Draws Controlled for PC's tray icons: a phone outline with Wi-Fi waves, as Windows expects in the notification area
# (a simple glyph, not the full-color logo). Variants: dark glyph for a light taskbar and white for a dark one; idle,
# "on" (an ember dot: a phone has control) and paused (two bars). Run: python pc/assets/tray.py
from PIL import Image, ImageDraw
import os

S = 256  # drawn large, then downscaled to 32 px for clean edges
EMBER = (255, 106, 61, 255)
PAUSE = (234, 179, 8, 255)


def glyph(color):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = 18  # stroke
    # Phone body, left-center.
    d.rounded_rectangle((40, 44, 132, 212), radius=22, outline=color, width=w)
    d.line((70, 182, 102, 182), fill=color, width=w)
    # Wi-Fi waves to the right, from the phone toward the PC.
    for r in (40, 78):
        d.arc((150 - r, 92 - r, 150 + r, 92 + r), start=-60, end=40, fill=color, width=w)
    return im


def badge(im, kind, ring):
    d = ImageDraw.Draw(im)
    c, r = (200, 200), 46
    d.ellipse((c[0] - r - 14, c[1] - r - 14, c[0] + r + 14, c[1] + r + 14), fill=ring)  # gap from the glyph
    if kind == "on":
        d.ellipse((c[0] - r, c[1] - r, c[0] + r, c[1] + r), fill=EMBER)
    else:
        d.ellipse((c[0] - r, c[1] - r, c[0] + r, c[1] + r), fill=PAUSE)
        for x in (c[0] - 20, c[0] + 8):
            d.rounded_rectangle((x, c[1] - 26, x + 14, c[1] + 26), radius=4, fill=(30, 30, 30, 255))
    return im


out = os.path.dirname(os.path.abspath(__file__))
for theme, color in (("light", (28, 28, 30, 255)), ("dark", (245, 245, 245, 255))):
    for state in ("idle", "on", "paused"):
        im = glyph(color)
        if state != "idle":
            # Cut a transparent ring around the badge so it reads on any taskbar color.
            cut = Image.new("L", (S, S), 255)
            ImageDraw.Draw(cut).ellipse((200 - 62, 200 - 62, 200 + 62, 200 + 62), fill=0)
            im.putalpha(Image.composite(im.getchannel("A"), Image.new("L", (S, S), 0), cut))
            im = badge(im, state, (0, 0, 0, 0))
        im.resize((32, 32), Image.LANCZOS).save(os.path.join(out, f"tray-{theme}-{state}.png"))
print("ok")
