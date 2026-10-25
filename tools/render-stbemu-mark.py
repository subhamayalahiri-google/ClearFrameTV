# Draws the white StbEmu mark (a TV on a stand) as a 240x240 PNG, like the other dock marks.
# Usage: python3 tools/render-stbemu-mark.py app/assets/icons/stbemu.png   (needs Pillow)
import sys
from PIL import Image, ImageDraw

S = 4  # drawn 4x, then scaled down for smooth edges
im = Image.new("RGBA", (240 * S, 240 * S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
white = (255, 255, 255, 255)
d.rounded_rectangle([6 * S, 30 * S, 234 * S, 182 * S], radius=22 * S, outline=white, width=18 * S)  # screen
d.rounded_rectangle([108 * S, 182 * S, 132 * S, 204 * S], radius=2 * S, fill=white)                # neck
d.rounded_rectangle([64 * S, 198 * S, 176 * S, 214 * S], radius=8 * S, fill=white)                  # base
im.resize((240, 240), Image.LANCZOS).save(sys.argv[1])
