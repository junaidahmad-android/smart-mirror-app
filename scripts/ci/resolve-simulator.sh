#!/usr/bin/env bash
# Prints the UDID of an available iOS simulator. Preference order:
#   $SIM_NAME (exact name, newest runtime) > iPhone 17 Pro > iPhone 16 Pro > any iPhone (newest runtime).
# Usage: udid="$(scripts/ci/resolve-simulator.sh)"
set -euo pipefail
xcrun simctl list devices available -j | SIM_NAME="${SIM_NAME:-}" python3 -c '
import json, os, re, sys
data = json.load(sys.stdin)["devices"]
def ver(runtime):
    m = re.search(r"iOS-(\d+)-(\d+)(?:-(\d+))?", runtime)
    return tuple(int(x or 0) for x in m.groups()) if m else None
devices = []
for runtime, devs in data.items():
    v = ver(runtime)
    if v is None:
        continue
    for d in devs:
        if d.get("isAvailable", True):
            devices.append((v, d["name"], d["udid"]))
devices.sort(reverse=True)
prefs = [n for n in (os.environ.get("SIM_NAME"), "iPhone 17 Pro", "iPhone 16 Pro") if n]
for name in prefs:
    for v, n, u in devices:
        if n == name:
            print(u); sys.exit(0)
for v, n, u in devices:
    if n.startswith("iPhone"):
        print(u); sys.exit(0)
sys.exit("No available iPhone simulator found")
'
