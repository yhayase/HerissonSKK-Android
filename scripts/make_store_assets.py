#!/usr/bin/env python3
"""指定アイコンの原本から Google Play 掲載用画像を生成します。"""

from pathlib import Path
import base64

import gi
from PIL import Image

gi.require_version("Rsvg", "2.0")
from gi.repository import Rsvg


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "HerissonSKK-icon-refined.png"
OUTPUT = ROOT / "assets/store"


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGBA")

    icon = source.resize((512, 512), Image.Resampling.LANCZOS)
    icon.save(OUTPUT / "app-icon-512.png", format="PNG", optimize=True)

    # Play の表示面で端が切り取られても主要部分が残る配置にします。
    source_data = base64.b64encode(SOURCE.read_bytes()).decode("ascii")
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <rect width="1024" height="500" fill="#294B59"/>
  <circle cx="263" cy="233" r="191" fill="#E9B85E"/>
  <image x="100" y="70" width="326" height="326" preserveAspectRatio="xMidYMid meet" href="data:image/png;base64,{source_data}"/>
  <text x="500" y="196" fill="#FFF8E9" font-family="DejaVu Sans, sans-serif" font-size="43" font-weight="700">HerissonSKK</text>
  <text x="503" y="245" fill="#E9B85E" font-family="DejaVu Sans, sans-serif" font-size="23">(for Android)</text>
  <path d="M503 274H848" stroke="#D5B877" stroke-width="2"/>
  <text x="500" y="346" fill="#FFF8E9" font-family="Noto Sans CJK JP, sans-serif" font-size="43" font-weight="700">SKK方式の日本語入力</text>
  <text x="503" y="393" fill="#D7E1DF" font-family="DejaVu Sans, sans-serif" font-size="23">Japanese SKK input</text>
</svg>
'''
    svg_path = OUTPUT / "feature-graphic.svg"
    svg_path.write_text(svg, encoding="utf-8")
    pixbuf = Rsvg.Handle.new_from_file(svg_path.as_uri()).get_pixbuf()
    rendered = OUTPUT / ".feature-graphic-render.png"
    pixbuf.savev(str(rendered), "png", [], [])
    with Image.open(rendered) as raster:
        raster.convert("RGB").save(
            OUTPUT / "feature-graphic-1024x500.png", format="PNG", optimize=True
        )
    rendered.unlink()


if __name__ == "__main__":
    main()
