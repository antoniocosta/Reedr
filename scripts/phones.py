#!/usr/bin/env python3
"""The phone picture for the README (docs/phones.png): Three Android screenshots side by side, each in a plain
phone outline, on the same backdrop as DocLock's (github.com/antoniocosta/DocLock, dev/phones.mjs). The same
script and look for every app of the collection.

usage: scripts/phones.py out.png shot1.png shot2.png shot3.png [--top PX] [--bottom PX]

The screenshots are full-screen captures (adb exec-out screencap -p): The system's status bar (--top, default
126 px, a 1080x2400 phone at 420 dpi) and gesture bar (--bottom, default 63 px) are cut off and drawn again by
the outline, in the colours of the app's own top and bottom edges. Needs Google Chrome (headless) and Pillow.
"""
import base64
import io
import os
import subprocess
import sys
import tempfile

from PIL import Image

args = sys.argv[1:]


def option(name, default):
    if name in args:
        i = args.index(name)
        value = int(args[i + 1])
        del args[i:i + 2]
        return value
    return default


top = option("--top", 126)
bottom = option("--bottom", 63)
if len(args) != 4:
    sys.exit(__doc__)
out, shots = args[0], args[1:]

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
W, H, PHONE_W = 900, 660, 250


def colours(img, y):
    """The colour of the screen's edge at row y, and black or white text on it."""
    r, g, b = img.convert("RGB").getpixel((img.width // 2 if y > 0 else 8, y))[:3]
    light = 0.299 * r + 0.587 * g + 0.114 * b > 140
    return f"rgb({r},{g},{b})", "#1d1d1f" if light else "#e9e9ee"


def phone(path):
    img = Image.open(path)
    img = img.crop((0, top, img.width, img.height - bottom))
    bg, fg = colours(img, 0)
    bottom_bg, bottom_fg = colours(img, img.height - 1)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    shot = base64.b64encode(buf.getvalue()).decode()
    height = round(PHONE_W * img.height / img.width)
    font = "600 11px -apple-system,Segoe UI,Roboto,Arial,sans-serif"
    return f"""<div style="padding:6px;border-radius:38px;background:#2a2c33;box-shadow:0 16px 50px rgb(0 0 0 / 40%)">
  <div style="width:{PHONE_W}px;border-radius:32px;overflow:hidden;background:{bg}">
    <div style="position:relative;height:30px;display:flex;align-items:center;justify-content:space-between;padding:0 25px;color:{fg};font:{font}">
      <span>9:41</span><span style="position:absolute;left:50%;top:9px;width:12px;height:12px;margin-left:-6px;border-radius:50%;background:#000"></span>
      <span style="display:flex;align-items:center;gap:1px"><span style="width:21px;height:10px;border:1px solid {fg};border-radius:3px;padding:1px;box-sizing:border-box;opacity:.9"><span style="display:block;width:80%;height:100%;border-radius:1.5px;background:{fg}"></span></span><span style="width:1.5px;height:4px;border-radius:0 1px 1px 0;background:{fg};opacity:.9"></span></span></div>
    <img src="data:image/png;base64,{shot}" style="display:block;width:{PHONE_W}px;height:{height}px">
    <div style="height:18px;display:flex;justify-content:center;align-items:center;background:{bottom_bg}"><span style="width:88px;height:4px;border-radius:2px;background:{bottom_fg};opacity:.55"></span></div>
  </div></div>"""


html = ('<!doctype html><meta charset="utf-8"><body style="margin:0;width:100vw;height:100vh;box-sizing:border-box;'
        'background:linear-gradient(135deg,#2b62d9,#7b7ff0);display:flex;justify-content:center;align-items:center;gap:36px">'
        + "".join(phone(s) for s in shots) + "</body>")
with tempfile.TemporaryDirectory() as tmp:
    page = os.path.join(tmp, "phones.html")
    open(page, "w").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={W},{H}",
                    "--force-device-scale-factor=2", f"--screenshot={os.path.abspath(out)}", f"file://{page}"],
                   check=True, capture_output=True)
print(out)
