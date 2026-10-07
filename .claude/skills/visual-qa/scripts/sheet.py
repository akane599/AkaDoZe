# Usage: python3 sheet.py <out.png> <cols> <img>...  -> contact sheet, each tile 360px wide
import sys
from PIL import Image, ImageDraw
out, cols, paths = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
W = 360
tiles = []
for p in paths:
    im = Image.open(p).convert("RGB")
    h = int(im.height * W / im.width)
    tiles.append((p.rsplit("/", 1)[-1].replace("_fs1.0.png", ""), im.resize((W, h))))
H = max(t[1].height for t in tiles) + 24
rows = (len(tiles) + cols - 1) // cols
sheet = Image.new("RGB", (cols * (W + 8), rows * (H + 8)), "#555")
d = ImageDraw.Draw(sheet)
for i, (name, im) in enumerate(tiles):
    x, y = (i % cols) * (W + 8), (i // cols) * (H + 8)
    d.text((x + 4, y + 4), name, fill="white")
    sheet.paste(im, (x, y + 24))
sheet.save(out)
