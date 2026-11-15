#!/usr/bin/env python3
"""Builds Cove's Minecraft vocabulary from the game's own language files.

The engine does not need a downloaded corpus: the language files that ship with
Minecraft already pair every item, block, mob, biome and effect in English and
Spanish. This script turns them into lexicon lines, so a player can say "iron",
"deepslate" or "warden" and Cove understands it, and so Spanish names that the
curated lexicon did not list (\"mena de hierro\", \"ladrillo del nether\") resolve
too.

Usage:
    python tools/gen_minecraft_terms.py <assets-dir> <output-file>

<assets-dir> is the instance's assets folder (contains indexes/ and objects/).
"""
import json
import os
import re
import sys
import unicodedata

STOPWORDS = {
    "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "y",
    "the", "of", "a", "an", "and", "block", "item", "decorated", "wall",
    "floor", "slab", "stairs", "pressure", "plate", "button", "door", "trapdoor",
}

CATEGORIES = (
    ("entity.minecraft.", "MOB"),
    ("biome.minecraft.", "PLACE"),
    ("item.minecraft.", "ITEM"),
    ("block.minecraft.", "ITEM"),
    ("effect.minecraft.", "STATE"),
    ("enchantment.minecraft.", "WORD"),
)


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFD", text)
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return text.lower().strip()


SPANISH_CODES = ("es_ar", "es_es", "es_mx", "es_cl", "es_uy", "es_ve")


def load_index(assets: str):
    """Newest local asset index that still has the Spanish language file."""
    indexes = os.path.join(assets, "indexes")
    best = None
    for name in os.listdir(indexes):
        if not name.endswith(".json") or name == "pre-1.6.json":
            continue
        try:
            with open(os.path.join(indexes, name), encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            continue
        objects = data.get("objects", {})
        has_spanish = any("minecraft/lang/%s.json" % code in objects for code in SPANISH_CODES)
        if not has_spanish:
            continue
        try:
            version = float(name[:-5].split("-")[0])
        except ValueError:
            version = 0.0
        score = (version, len(objects))
        if best is None or score > best[0]:
            best = (score, objects)
    return best[1] if best else {}


def object_path(assets: str, entry: dict) -> str:
    digest = entry["hash"]
    return os.path.join(assets, "objects", digest[:2], digest)


def read_lang(assets: str, objects: dict, code: str):
    key = "minecraft/lang/%s.json" % code
    if key not in objects:
        return {}
    path = object_path(assets, objects[key])
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def single_words(*names):
    words = []
    for name in names:
        for token in re.split(r"[^0-9A-Za-zÀ-ÿ]+", name):
            token = normalize(token)
            if len(token) < 3 or token in STOPWORDS or token.isdigit():
                continue
            if token not in words:
                words.append(token)
    return words[:6]


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    assets, output = sys.argv[1], sys.argv[2]
    objects = load_index(assets)
    if not objects:
        print("no asset index with language files found under", assets)
        return 1
    en = read_lang(assets, objects, "en_us")  # often absent: the key path is English anyway
    es = {}
    for code in ("es_ar", "es_es", "es_mx", "es_cl", "es_uy"):
        es = read_lang(assets, objects, code)
        if es:
            break
    if not es:
        print("no Spanish language file found; the script needs es_ar/es_es")
        return 1

    lines = ["# Generado desde los archivos de idioma de Minecraft.",
             "# No editar a mano: correr tools/gen_minecraft_terms.py",
             "# Formato: CATEGORIA|canonico|sinonimos en espanol e ingles"]
    seen = set()
    for key in sorted(es):
        category = next((cat for prefix, cat in CATEGORIES if key.startswith(prefix)), None)
        if category is None:
            continue
        spanish = es[key]
        english = en.get(key) or key.split(".", 2)[-1].replace("_", " ")
        if not spanish or "%" in spanish:
            continue
        canonical = normalize(spanish)
        if not canonical or canonical in seen:
            continue
        seen.add(canonical)
        synonyms = [w for w in single_words(spanish, english) if w != canonical]
        if not synonyms:
            continue
        line = "%s|%s|%s" % (category, canonical, ",".join(synonyms))
        lines.append(line)

    with open(output, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    print("wrote %d entries to %s" % (len(lines) - 3, output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
