from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
    ]
    for candidate in candidates:
        path = Path(candidate)
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def draw_shield(draw: ImageDraw.ImageDraw, center: tuple[int, int], scale: int) -> None:
    cx, cy = center
    points = [
        (cx, cy - 58 * scale),
        (cx + 48 * scale, cy - 36 * scale),
        (cx + 42 * scale, cy + 26 * scale),
        (cx, cy + 62 * scale),
        (cx - 42 * scale, cy + 26 * scale),
        (cx - 48 * scale, cy - 36 * scale),
    ]
    draw.polygon(points, fill=(235, 245, 252), outline=(96, 210, 242), width=4 * scale)
    draw.line([(cx, cy - 38 * scale), (cx, cy + 35 * scale)], fill=(23, 50, 70), width=5 * scale)
    draw.arc(
        [cx - 22 * scale, cy - 10 * scale, cx + 22 * scale, cy + 34 * scale],
        205,
        335,
        fill=(23, 50, 70),
        width=5 * scale,
    )


def main() -> None:
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("levik-banner.png")
    width, height = 1280, 520
    image = Image.new("RGB", (width, height), (10, 20, 30))
    draw = ImageDraw.Draw(image)

    for y in range(height):
        blend = y / height
        color = (
            int(11 + 16 * blend),
            int(22 + 34 * blend),
            int(35 + 45 * blend),
        )
        draw.line([(0, y), (width, y)], fill=color)

    for i in range(90):
        angle = i * 0.41
        radius = 70 + (i * 37) % 560
        x = int(width * 0.72 + math.cos(angle) * radius)
        y = int(height * 0.45 + math.sin(angle) * radius * 0.45)
        if 0 <= x < width and 0 <= y < height:
            shade = 55 + (i % 5) * 18
            draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(shade, shade + 25, shade + 38))

    for x in range(-120, width, 115):
        draw.line([(x, height), (x + 360, 0)], fill=(18, 56, 77), width=2)

    draw.rounded_rectangle((42, 40, width - 42, height - 40), radius=34, outline=(78, 179, 214), width=3)
    draw_shield(draw, (195, 225), 2)

    title_font = load_font(82, bold=True)
    subtitle_font = load_font(36, bold=False)
    small_font = load_font(26, bold=False)

    draw.text((340, 160), "Levik VPN", font=title_font, fill=(245, 250, 255))
    draw.text((344, 260), "быстрый приватный доступ", font=subtitle_font, fill=(126, 222, 247))
    draw.text((344, 330), "Private access service", font=small_font, fill=(168, 186, 199))

    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, "PNG", optimize=True)


if __name__ == "__main__":
    main()
