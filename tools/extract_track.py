import argparse
import io
import json
import re
import shutil
import struct
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter
from generate_background import create_background


def milliseconds(value):
    minutes, seconds = value.split(":")
    return round((int(minutes) * 60 + float(seconds)) * 1000)


def extract(source, project):
    tags = {}
    picture = None
    duration = 0
    sample_rate = 0
    bits = 0
    with source.open("rb") as stream:
        if stream.read(4) != b"fLaC":
            raise ValueError("Not a FLAC file")
        while True:
            header = stream.read(4)
            if len(header) != 4:
                raise ValueError("Truncated FLAC metadata")
            data = stream.read(int.from_bytes(header[1:4], "big"))
            kind = header[0] & 127
            if kind == 0:
                packed = int.from_bytes(data[10:18], "big")
                sample_rate = packed >> 44
                duration = round((packed & ((1 << 36) - 1)) / sample_rate * 1000)
                bits = ((packed >> 36) & 31) + 1
            elif kind == 4:
                offset = 4 + int.from_bytes(data[:4], "little")
                count = int.from_bytes(data[offset:offset + 4], "little")
                offset += 4
                for entry in range(count):
                    length = int.from_bytes(data[offset:offset + 4], "little")
                    offset += 4
                    value = data[offset:offset + length].decode("utf-8", "replace")
                    offset += length
                    key, separator, content = value.partition("=")
                    if separator:
                        tags[key.upper()] = content
            elif kind == 6:
                offset = 4
                mime_length = int.from_bytes(data[offset:offset + 4], "big")
                offset += 4 + mime_length
                description_length = int.from_bytes(data[offset:offset + 4], "big")
                offset += 4 + description_length + 16
                length = int.from_bytes(data[offset:offset + 4], "big")
                picture = data[offset + 4:offset + 4 + length]
            if header[0] & 128:
                break

    assets = project / "app/src/main/assets"
    drawable = project / "app/src/main/res/drawable-nodpi"
    assets.mkdir(parents=True, exist_ok=True)
    drawable.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, assets / "track.flac")
    if picture is None:
        raise ValueError("FLAC has no embedded cover")
    cover = Image.open(io.BytesIO(picture)).convert("RGB")
    side = min(cover.size)
    left = (cover.width - side) // 2
    top = (cover.height - side) // 2
    cover = cover.crop((left, top, left + side, top + side))
    cover.resize((1200, 1200), Image.Resampling.LANCZOS).save(drawable / "cover.jpg", quality=94)
    texture = cover.resize((360, 780), Image.Resampling.BILINEAR).filter(ImageFilter.GaussianBlur(74))
    texture = ImageEnhance.Color(texture).enhance(0.72)
    texture = ImageEnhance.Brightness(texture).enhance(0.57)
    texture = Image.blend(texture, Image.new("RGB", texture.size, "#684f50"), 0.25)
    texture.save(drawable / "player_background.jpg", quality=91)
    lyric_texture = Image.blend(texture, Image.new("RGB", texture.size, "#151c32"), 0.58)
    lyric_texture.save(drawable / "lyrics_background.jpg", quality=90)
    create_background(project)

    line_pattern = re.compile(r"^\[(\d+:\d+(?:\.\d+)?)\](.*)$")
    word_pattern = re.compile(r"<(\d+:\d+(?:\.\d+)?)>([^<]*)")
    raw_lyrics = tags.get("LYRICS", tags.get("UNSYNCEDLYRICS", ""))
    lines = []
    for raw in raw_lyrics.splitlines():
        matched = line_pattern.match(raw.strip())
        if not matched:
            continue
        start = milliseconds(matched.group(1))
        timed = [(milliseconds(token.group(1)), token.group(2)) for token in word_pattern.finditer(matched.group(2))]
        words = []
        for index, (word_start, text) in enumerate(timed):
            if not text:
                continue
            word_end = timed[index + 1][0] if index + 1 < len(timed) else word_start + 500
            words.append({"text": text, "startMs": word_start, "endMs": max(word_start + 1, word_end)})
        if not words:
            text = re.sub(r"<[^>]*>", "", matched.group(2)).strip()
            if text:
                words = [{"text": text, "startMs": start, "endMs": start + 3000}]
        if words:
            lines.append({"startMs": start, "endMs": words[-1]["endMs"], "words": words})
    lines.sort(key=lambda line: line["startMs"])
    for index, line in enumerate(lines):
        next_start = lines[index + 1]["startMs"] if index + 1 < len(lines) else duration
        line["endMs"] = min(duration, max(line["endMs"], next_start))

    metadata = {
        "title": tags.get("TITLE", source.stem),
        "artist": tags.get("ARTIST", ""),
        "album": tags.get("ALBUM", ""),
        "durationMs": duration,
        "sampleRate": sample_rate,
        "bits": bits,
        "lines": lines,
    }
    (assets / "track.json").write_text(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (assets / "track.lrc").write_text(raw_lyrics, encoding="utf-8")
    print(json.dumps({"title": metadata["title"], "durationMs": duration, "sampleRate": sample_rate, "bits": bits, "cover": cover.size, "lines": len(lines), "words": sum(len(line["words"]) for line in lines)}, ensure_ascii=False))
    for line in lines[15:19]:
        print(line["startMs"], "".join(word["text"] for word in line["words"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("project", type=Path)
    options = parser.parse_args()
    extract(options.source, options.project)
