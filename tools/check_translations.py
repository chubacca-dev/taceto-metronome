"""Check completeness and format placeholders of supported app translations."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")


def entries(path):
    tree = ET.parse(path).getroot()
    result = {}
    for node in tree:
        key = (node.tag, node.attrib["name"])
        if key in result:
            raise ValueError(f"Duplicate resource {key} in {path}")
        result[key] = node
    return result


def formats(node):
    return sorted(FORMAT.findall("".join(node.itertext())))


def check():
    defaults = entries(RES / "values/strings.xml")
    required = {key for key, node in defaults.items() if node.get("translatable") != "false"}
    for locale in ET.parse(RES / "xml/locales_config.xml").getroot():
        tag = locale.get("{http://schemas.android.com/apk/res/android}name")
        if tag == "en":
            continue  # Complete English defaults are the fallback.
        translations = entries(RES / f"values-{tag}/strings.xml")
        if required - translations.keys() or translations.keys() - defaults.keys():
            raise ValueError(f"{tag}: missing={required - translations.keys()}, unknown={translations.keys() - defaults.keys()}")
        for key, translated in translations.items():
            original = defaults[key]
            if translated.tag == "plurals":
                originals = {item.get("quantity"): item for item in original}
                if not any(item.get("quantity") == "other" for item in translated):
                    raise ValueError(f"{tag}: plural {key} needs an 'other' entry")
                for item in translated:
                    reference = originals.get(item.get("quantity"), originals["other"])
                    if formats(reference) != formats(item):
                        raise ValueError(f"{tag}: mismatched plural format {key}")
            elif formats(original) != formats(translated):
                raise ValueError(f"{tag}: mismatched string format {key}")
        print(f"{tag}: {len(translations)} resources complete, placeholders match")


if __name__ == "__main__":
    check()
