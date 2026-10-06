import argparse
import json
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


def build_fonts(project, source):
    destination = project / "app/src/main/res/font"
    licenses = project / "app/src/main/assets/licenses"
    destination.mkdir(parents=True, exist_ok=True)
    licenses.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((project / "app/src/main/assets/track.json").read_text())
    characters = json.dumps(metadata, ensure_ascii=False) + "无损如诗音乐播放暂停音量收藏歌词歌曲信息本机扬声器关闭蓝牙设置待播清单"
    characters += "".join(chr(code) for code in range(32, 127)) + "−–—…·：，。！？（）"
    for family, upstream, label in [("latin", "inter", "Latin"), ("cjk", "noto", "Chinese")]:
        original = TTFont(source / f"{upstream}.ttf")
        options = subset.Options()
        options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
        options.name_legacy = True
        options.name_languages = [0x409]
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(text=characters)
        subsetter.subset(original)
        subset_path = source / f"{family}-subset.ttf"
        original.save(subset_path)
        for weight, style in [(400, "Regular"), (500, "Medium")]:
            font = TTFont(subset_path)
            axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
            axes["wght"] = weight
            if "opsz" in axes:
                axes["opsz"] = 20
            instantiateVariableFont(font, axes, inplace=True)
            family_name = f"Rushi {label}"
            names = {1: family_name, 2: style, 3: f"{family_name}-{style}", 4: f"{family_name} {style}", 6: f"Rushi{label}-{style}"}
            for name_id, value in names.items():
                font["name"].setName(value, name_id, 3, 1, 0x409)
            output = destination / f"player_{family}_{style.lower()}.ttf"
            font.save(output)
            print(output.name, output.stat().st_size)
        remote = "inter" if family == "latin" else "notosanssc"
        license_text = urllib.request.urlopen(f"https://raw.githubusercontent.com/google/fonts/main/ofl/{remote}/OFL.txt", timeout=30).read()
        (licenses / f"{remote}-OFL.txt").write_bytes(license_text)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    parser.add_argument("source", type=Path)
    parameters = parser.parse_args()
    build_fonts(parameters.project, parameters.source)
