"""生成 README 用的表情总图 docs/expressions.png。

仓库里原来没有这个脚本（当时是一次性做的），这里补一个放 tools/，
以后换了立绘重跑一次就行：
    python tools/make_expressions_sheet.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKINS = [
    ("饭盆头", os.path.join(ROOT, "app", "src", "main", "assets", "skins", "basin")),
    ("女仆装", os.path.join(ROOT, "app", "src", "main", "assets", "skins", "maid")),
]
FACES = [
    ("pet_stand.png", "normal"),
    ("pet_face_happy.png", "happy"),
    ("pet_face_sad.png", "sad"),
    ("pet_face_angry.png", "angry"),
    ("pet_face_surprised.png", "surprised"),
    ("pet_face_shy.png", "shy"),
    ("pet_face_confused.png", "confused"),
]
OUT = os.path.join(ROOT, "docs", "expressions.png")

BG = (22, 32, 46)
FG = (232, 238, 246)
DIM = (150, 165, 185)
RULE = (70, 88, 112)

PAD = 26
COLS = 4
CELL_W, CELL_H = 196, 258
GAP = 12
TITLE_H = 74
SECTION_GAP = 34


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "simhei.ttf"]):
        p = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def mono(size):
    p = os.path.join(r"C:\Windows\Fonts", "consola.ttf")
    return ImageFont.truetype(p, size) if os.path.exists(p) else ImageFont.load_default()


def rows_for(n):
    return (n + COLS - 1) // COLS


def main():
    total_rows = sum(rows_for(len(FACES)) for _, _ in SKINS)
    W = PAD * 2 + COLS * CELL_W + (COLS - 1) * GAP
    H = PAD * 2 + len(SKINS) * (TITLE_H + SECTION_GAP) + total_rows * (CELL_H + GAP)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    f_title = font(34, bold=True)
    f_label = mono(19)

    y = PAD
    for skin_name, skin_dir in SKINS:
        d.text((PAD, y), skin_name, font=f_title, fill=FG)
        tw = d.textlength(skin_name, font=f_title)
        d.line([(PAD + tw + 14, y + 24), (W - PAD, y + 24)], fill=RULE, width=2)
        y += TITLE_H
        for i, (fn, label) in enumerate(FACES):
            c, r = i % COLS, i // COLS
            x = PAD + c * (CELL_W + GAP)
            cy = y + r * (CELL_H + GAP)
            path = os.path.join(skin_dir, fn)
            if not os.path.exists(path):
                continue
            sp = Image.open(path).convert("RGBA")
            box = 232
            sp.thumbnail((CELL_W - 12, box), Image.LANCZOS)
            img.paste(sp, (x + (CELL_W - sp.width) // 2, cy + (box - sp.height)), sp)
            d.text((x + (CELL_W - d.textlength(label, font=f_label)) / 2, cy + box + 8),
                   label, font=f_label, fill=DIM)
        y += rows_for(len(FACES)) * (CELL_H + GAP) + SECTION_GAP

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    print("  [OK] %s  %dx%d  %.0f KB" % (OUT, img.width, img.height, os.path.getsize(OUT) / 1024.0))


if __name__ == "__main__":
    main()
