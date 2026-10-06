import argparse
import math
from pathlib import Path

from PIL import Image, ImageFilter, ImageStat


def create_background(project):
    resources = project / "app/src/main/res/drawable-nodpi"
    cover = Image.open(resources / "cover.jpg").convert("RGB")

    def sample(bounds, brightness):
        region = cover.crop(tuple(round(value * cover.width) for value in bounds))
        return tuple(min(180, channel * brightness) for channel in ImageStat.Stat(region).mean)

    warm = sample((0.65, 0.75, 0.98, 0.97), 1.30)
    violet = sample((0.45, 0.44, 0.59, 0.65), 2.1)
    shadow = sample((0.1, 0.13, 0.35, 0.43), 1.8)
    width, height = 240, 520
    image = Image.new("RGB", (width, height))
    pixels = []
    for vertical in range(height):
        fraction_y = vertical / height
        for horizontal in range(width):
            fraction_x = horizontal / width
            warm_weight = math.exp(-((fraction_x - 0.12) ** 2 / 0.55 + (fraction_y - 0.36) ** 2 / 0.13))
            violet_weight = math.exp(-((fraction_x - 0.93) ** 2 / 0.7 + (fraction_y - 0.83) ** 2 / 0.3))
            base = tuple(channel + 25 for channel in shadow)
            pixels.append(tuple(round(max(0, min(255, base[channel] + (warm[channel] - base[channel]) * warm_weight * 0.7 + (violet[channel] - base[channel]) * violet_weight * 0.7))) for channel in range(3)))
    image.putdata(pixels)
    image = image.resize((480, 1040), Image.Resampling.BICUBIC).filter(ImageFilter.GaussianBlur(16))
    image.save(resources / "player_background.jpg", quality=95)
    print("Generated continuous cover-derived background", image.size)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    create_background(parser.parse_args().project)
