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
import urllib.parse
import urllib.request

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


print(f"Mods for {MC} {LOADER}, pack {PACK}:")
for i, project in enumerate(PACKS[PACK]):
    add(project, i == 0)  # AE2 is required; add-ons a version lacks are skipped
if PACK == "gtceu":
    if MC not in GTCEU:
        missing.append(f"gtceu: no build for {MC}")
    else:
        download(GTCEU[MC], os.path.basename(GTCEU[MC]))
if missing:
    print("::error::" + "; ".join(missing))
    sys.exit(1)
print("\n".join(sorted(os.listdir(DEST))))
