# Turns launcher art (anything Pillow reads - the checked-in source is the .ico picked for this
# app) into the mipmap set: legacy square PNGs for old launchers, plus adaptive-icon foregrounds
# drawn on a 108dp canvas.
#
#     python make-icon.py [art]        (default: android/icon/1.1.ico)
#
# The adaptive icon itself is app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml, which paints
# @color/ic_bg behind the foreground. That colour is sampled from the art's own background, so the
# art's rounded corners vanish against the canvas and only the drawing reads as the icon.
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ART = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "icon", "1.1.ico")
RES = os.path.join(HERE, "app", "src", "main", "res")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48        # classic launcher icon
FOREGROUND_DP = 108   # adaptive icon canvas
ART_DP = 76           # how much of that canvas the drawing gets (fits the safe zone)

art_src = Image.open(ART).convert("RGBA")

written = 0
for name, scale in DENSITIES.items():
    out = os.path.join(RES, "mipmap-" + name)
    os.makedirs(out, exist_ok=True)

    side = round(LEGACY_DP * scale)
    art_src.resize((side, side), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    written += 1

    canvas_side = round(FOREGROUND_DP * scale)
    canvas = Image.new("RGBA", (canvas_side, canvas_side), (0, 0, 0, 0))
    art_side = round(ART_DP * scale)
    art = art_src.resize((art_side, art_side), Image.LANCZOS)
    offset = (canvas_side - art_side) // 2
    canvas.paste(art, (offset, offset), art)
    canvas.save(os.path.join(out, "ic_fg.png"))
    written += 1

print("%s: %dx%d -> %d png files under %s"
      % (ART, art_src.width, art_src.height, written, RES))
