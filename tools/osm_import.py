#!/usr/bin/env python3
"""
OPTIONAL: import real OpenStreetMap geometry to replace the hand-authored skeleton.

**This script was never run for the shipped build.** The build environment had no
network access to any geodata host, so every coastline, road and district in the APK
is hand-authored (see `DATA_SOURCES.md`). Nothing produced by this script is in the
repository or in the APK.

If you run it yourself you take on two obligations:

1. **Attribution.** OpenStreetMap data is licensed ODbL 1.0. Any build that contains
   OSM-derived geometry must display "(c) OpenStreetMap contributors" where users can
   see it, and must say so in its documentation. Pass `--accept-odbl` to acknowledge
   this; the script writes an `OSM_ATTRIBUTION_REQUIRED` marker next to its output and
   `build_world.py` will refuse to package OSM-derived layers without it.
2. **Overpass etiquette.** The public Overpass endpoints are a donated resource. This
   script requests one bbox at a time, backs off on 429/504, and caches aggressively.
   Do not loop it.

Usage::

    python3 tools/osm_import.py --accept-odbl --out data/osm
    python3 tools/build_world.py --osm data/osm

Output is written as plain GeoJSON so you can inspect, edit or discard it before it
reaches the world compiler.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mumbai_geo as geo                                     # noqa: E402

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

# Each query is (output name, Overpass QL body). Kept small and specific so a single
# failure does not cost the whole import.
QUERIES = {
    "coastline": 'way["natural"="coastline"]({bbox});',
    "water": 'relation["natural"="water"]({bbox});way["natural"="water"]({bbox});',
    "roads": ('way["highway"~"^(motorway|trunk|primary|secondary|tertiary)$"]'
              '({bbox});'),
    "railways": 'way["railway"~"^(rail|subway|light_rail|monorail)$"]({bbox});',
    "landuse": ('way["landuse"~"^(residential|industrial|commercial|retail|'
                'forest|farmland)$"]({bbox});'),
    "places": 'node["place"~"^(suburb|neighbourhood|quarter|town)$"]({bbox});',
    "buildings": 'way["building"]({bbox});',
    "aeroway": 'way["aeroway"~"^(runway|taxiway|apron)$"]({bbox});',
}


def bbox_string():
    b = geo.AOI_BOUNDS
    return f'{b["min_lat"]},{b["min_lon"]},{b["max_lat"]},{b["max_lon"]}'


def fetch(name, body, out_dir, timeout=180):
    cache = os.path.join(out_dir, f"{name}.osm.json")
    if os.path.exists(cache) and os.path.getsize(cache) > 0:
        print(f"  {name}: cached")
        return cache

    query = f"[out:json][timeout:{timeout}];({body.format(bbox=bbox_string())});out geom;"
    data = urllib.parse.urlencode({"data": query}).encode()
    last = None
    for attempt in range(4):
        for endpoint in ENDPOINTS:
            try:
                print(f"  {name}: querying {endpoint} (attempt {attempt + 1})")
                req = urllib.request.Request(
                    endpoint, data=data,
                    headers={"User-Agent": "mumbai-island-city/1.0 (optional import)"})
                with urllib.request.urlopen(req, timeout=timeout + 30) as resp:
                    payload = resp.read()
                with open(cache, "wb") as fh:
                    fh.write(payload)
                print(f"  {name}: {len(payload) // 1024} KB")
                return cache
            except (urllib.error.HTTPError, urllib.error.URLError, OSError) as exc:
                last = exc
                print(f"    failed: {exc}")
                time.sleep(5 * (attempt + 1))
    raise SystemExit(f"{name}: all endpoints failed, last error {last}")


def to_geojson(osm_path, out_path):
    with open(osm_path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    features = []
    for el in doc.get("elements", []):
        tags = el.get("tags", {})
        if el.get("type") == "node":
            geom = {"type": "Point", "coordinates": [el["lon"], el["lat"]]}
        elif "geometry" in el:
            coords = [[p["lon"], p["lat"]] for p in el["geometry"]]
            if len(coords) < 2:
                continue
            closed = coords[0] == coords[-1] and len(coords) > 3
            geom = ({"type": "Polygon", "coordinates": [coords]} if closed
                    else {"type": "LineString", "coordinates": coords})
        else:
            continue
        features.append({"type": "Feature", "properties": tags, "geometry": geom})
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump({"type": "FeatureCollection", "features": features}, fh)
    return len(features)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="data/osm", help="output directory")
    ap.add_argument("--accept-odbl", action="store_true",
                    help="acknowledge the ODbL attribution obligation")
    ap.add_argument("--only", nargs="*", choices=sorted(QUERIES),
                    help="import only these layers")
    args = ap.parse_args()

    if not args.accept_odbl:
        print(__doc__)
        print("Refusing to run without --accept-odbl.")
        return 2

    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "OSM_ATTRIBUTION_REQUIRED"), "w",
              encoding="utf-8") as fh:
        fh.write("Data in this directory is derived from OpenStreetMap and is\n"
                 "licensed ODbL 1.0. Any build containing it MUST display\n"
                 '"(c) OpenStreetMap contributors" to the user.\n')

    names = args.only or list(QUERIES)
    print(f"AOI bbox {bbox_string()}")
    for name in names:
        raw = fetch(name, QUERIES[name], out_dir)
        n = to_geojson(raw, os.path.join(out_dir, f"{name}.geojson"))
        print(f"  {name}: {n} features -> {name}.geojson")

    print()
    print("Done. Remember: enabling this path makes OSM attribution mandatory,")
    print("in the app UI and in DATA_SOURCES.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
