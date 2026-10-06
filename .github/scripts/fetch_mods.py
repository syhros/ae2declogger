#!/usr/bin/env python3
"""Download the mods for a CI server test into a mods folder.

    fetch_mods.py <minecraft version> <forge|neoforge> <pack> <mods dir>

Packs:
  ae2      AE2 alone
  addons   AE2 with the popular add-ons that exist for this version (Advanced AE, Extended AE, MEGA Cells, AE2
           Wireless Terminals, Applied Flux), so conflicts and add-on CPUs/providers show up
  gtceu    AE2 with GregTech CEu Modern (1.20.1)

Takes the newest release (else beta) from Modrinth for the version and loader, plus required dependencies.
AE2 builds Modrinth does not have (1.18.2) come from AE2's GitHub releases.
"""
import json
import os
import sys
import time
import tomllib
import urllib.parse
import urllib.request
import zipfile

MC, LOADER, PACK, DEST = sys.argv[1:5]
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "syhros/ae2-debugger CI (github.com/syhros/ae2-debugger)"}

PACKS = {
    "ae2": ["ae2"],
    "addons": ["ae2", "advancedae", "extended-ae", "mega", "applied-energistics-2-wireless-terminals", "appflux"],
    "gtceu": ["ae2"],
}
# AE2 releases that are only on GitHub (and CurseForge).
GITHUB_AE2 = {
    "1.18.2": "https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/download/forge%2Fv11.7.6/"
              "appliedenergistics2-forge-11.7.6.jar",
}
GTCEU = {
    "1.20.1": "https://github.com/GregTechCEu/GregTech-Modern/releases/download/v7.5.3-1.20.1/gtceu-1.20.1-7.5.3.jar",
}


def get(url):
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - retry anything, report the last error
            if attempt == 4:
                raise
            print(f"  retry {url}: {e}")
            time.sleep(3 * (attempt + 1))


def versions_of(project):
    q = urllib.parse.urlencode({"game_versions": json.dumps([MC]), "loaders": json.dumps([LOADER])})
    return json.loads(get(f"{API}/project/{project}/version?{q}"))


def pick(versions):
    for kind in ("release", "beta", "alpha"):
        for v in versions:
            if v["version_type"] == kind:
                return v
    return None


def download(url, name):
    path = os.path.join(DEST, name)
    if not os.path.exists(path):
        with open(path, "wb") as f:
            f.write(get(url))
        print(f"  {name}")


os.makedirs(DEST, exist_ok=True)
seen = set()
missing = []


def add(project, required):
    info = json.loads(get(f"{API}/project/{project}"))
    if info["id"] in seen:
        return
    seen.add(info["id"])
    if info["slug"] == "ae2" and MC in GITHUB_AE2:
        download(GITHUB_AE2[MC], os.path.basename(urllib.parse.unquote(GITHUB_AE2[MC])))
        return
    v = pick(versions_of(info["id"]))
    if v is None:
        message = f"{info['slug']}: no build for {MC} {LOADER}"
        if required:
            missing.append(message)
        else:
            print(f"  skipped {message}")
        return
    files = [f for f in v["files"] if f["primary"]] or v["files"]
    download(files[0]["url"], files[0]["filename"])
    for dep in v.get("dependencies", []):
        if dep["dependency_type"] == "required" and dep.get("project_id"):
            add(dep["project_id"], True)


# Mod ids whose Modrinth project slug is not the id itself (or the id with - for _).
SLUGS = {"architectury": "architectury-api", "cloth_config": "cloth-config"}
BUILTIN = {"minecraft", "forge", "neoforge", "java", "fml", "javafml", "lowcodefml"}


def jar_mods(path):
    """(mod ids, mandatory dependency ids) from a jar's mods.toml / neoforge.mods.toml."""
    try:
        with zipfile.ZipFile(path) as z:
            names = [n for n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if n in z.namelist()]
            if not names:
                return set(), set()
            data = tomllib.loads(z.read(names[0]).decode("utf8", "replace"))
    except Exception as e:  # noqa: BLE001 - a jar we cannot read adds nothing
        print(f"  could not read the mod list of {os.path.basename(path)}: {e}")
        return set(), set()
    ids = {m.get("modId") for m in data.get("mods", []) if m.get("modId")}
    deps = set()
    for entries in data.get("dependencies", {}).values():
        for d in entries if isinstance(entries, list) else []:
            if d.get("mandatory") is True or str(d.get("type", "")).lower() == "required":
                deps.add(d.get("modId"))
    return ids, deps - BUILTIN - {None}


def add_jar_dependencies():
    """Modrinth's dependency lists are incomplete for some older builds; follow what each jar itself requires."""
    tried = set()
    while True:
        present, wanted = set(), set()
        for name in os.listdir(DEST):
            if name.endswith(".jar"):
                ids, deps = jar_mods(os.path.join(DEST, name))
                present |= ids
                wanted |= deps
        todo = sorted(wanted - present - tried)
        if not todo:
            for mod_id in sorted(wanted - present):
                missing.append(f"{mod_id}: required by a downloaded mod, no build found for {MC} {LOADER}")
            return
        for mod_id in todo:
            tried.add(mod_id)
            slug = SLUGS.get(mod_id, mod_id.replace("_", "-"))
            print(f"  {mod_id} is required by a downloaded jar, fetching {slug}")
            try:
                add(slug, True)
            except Exception as e:  # noqa: BLE001 - reported as missing once the loop ends
                print(f"  {slug}: {e}")


print(f"Mods for {MC} {LOADER}, pack {PACK}:")
for i, project in enumerate(PACKS[PACK]):
    add(project, i == 0)  # AE2 is required; add-ons a version lacks are skipped
add_jar_dependencies()
if PACK == "gtceu":
    if MC not in GTCEU:
        missing.append(f"gtceu: no build for {MC}")
    else:
        download(GTCEU[MC], os.path.basename(GTCEU[MC]))
if missing:
    print("::error::" + "; ".join(missing))
    sys.exit(1)
print("\n".join(sorted(os.listdir(DEST))))
