#!/usr/bin/env python3
"""Release notes from .github/RELEASE_NOTES.md: @VERSION@ and @JARS@ (a table of the jar for each version).

    release_notes.py <mod version> <folder with the release jars>
"""
import os
import pathlib
import re
import sys

version, folder = sys.argv[1], sys.argv[2]
rows = []
names = {"forge": "Forge", "neoforge": "NeoForge"}


def mc_key(mc):
    return [int(p) for p in mc.split(".")]


for jar in os.listdir(folder):
    m = re.fullmatch(r"ae2declogger-(forge|neoforge)-([\d.]+)-" + re.escape(version) + r"\.jar", jar)
    if m:
        rows.append((mc_key(m.group(2)), f"| {m.group(2)} | {names[m.group(1)]} | `{jar}` |"))
table = "| Minecraft | Loader | File |\n|---|---|---|\n" + "\n".join(r for _, r in sorted(rows))
root = pathlib.Path(__file__).resolve().parents[2]
text = (root / ".github" / "RELEASE_NOTES.md").read_text(encoding="utf8")
print(text.replace("@VERSION@", version).replace("@JARS@", table))
