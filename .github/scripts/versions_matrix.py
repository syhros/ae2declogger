#!/usr/bin/env python3
"""Print the CI matrices for every version under versions/ (one subproject per Minecraft version).

Writes to $GITHUB_OUTPUT:
  versions  [{"name": "neoforge-1.21.1", "mc": "1.21.1", "loader": "neoforge", "java": "21", "loaderVersion": ...}]
  smoke     the versions crossed with the server test packs (ae2, addons, and gtceu where GregTech is built)
"""
import json
import os
import pathlib

root = pathlib.Path(__file__).resolve().parents[2]
versions = []
for d in sorted((root / "versions").iterdir()):
    props = {}
    f = d / "version.properties"
    if not f.exists():
        continue
    for line in f.read_text(encoding="utf8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    loader = props["loader"]
    versions.append({
        "name": d.name,
        "mc": props["minecraft_version"],
        "loader": loader,
        "java": props["java_version"],
        "loaderVersion": props["forge_version"] if loader == "forge" else props["neoforge_version"],
        "gtceu": "gtceu_version" in props,
    })

smoke = []
for v in versions:
    for pack in ["ae2", "addons"] + (["gtceu"] if v["gtceu"] else []):
        smoke.append({**v, "pack": pack})

out = os.environ.get("GITHUB_OUTPUT")
text = f"versions={json.dumps(versions)}\nsmoke={json.dumps(smoke)}\n"
if out:
    with open(out, "a", encoding="utf8") as fh:
        fh.write(text)
print(text)
