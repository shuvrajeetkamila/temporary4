#!/usr/bin/env python3
"""
Stage 1 of the world pipeline: obtain real OpenStreetMap data for the AOI.

This downloads a regional extract from Geofabrik and clips it to the AOI polygon
with osmium. It records full provenance — URL, HTTP date, byte size, SHA-256 of
what was actually downloaded, and the OSM replication timestamp baked into the
file header — into data/provenance/osm.json, so that the licence attribution in
DATA_SOURCES.md refers to a specific, verifiable artefact rather than to "OSM"
in the abstract.

Must run somewhere with internet access. The development sandbox for this
project cannot reach Geofabrik, so this is invoked from GitHub Actions
(.github/workflows/build-world.yml) and its output is committed back.

  python3 tools/fetch_osm.py --out data/osm
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aoi  # noqa: E402

# Geofabrik publishes daily extracts under a stable path. Maharashtra is the
# smallest standard extract that fully contains Salsette, Vasai Creek and the
# Thane Creek mainland shore.
PRIMARY = "https://download.geofabrik.de/asia/india/maharashtra-latest.osm.pbf"
PRIMARY_MD5 = PRIMARY + ".md5"

# If Geofabrik is unavailable, BBBike can cut a custom box. Slower and rate
# limited, so it is genuinely a fallback.
FALLBACK = ("https://download.bbbike.org/osm/bbbike/Mumbai/Mumbai.osm.pbf")

USER_AGENT = ("MumbaiIslandCity-worldbuild/2.0 "
              "(+https://github.com/shuvrajeetkamila/temporary4; offline game world build)")


def _log(msg):
    print(f"[fetch_osm] {msg}", flush=True)


def _download(url, dest, retries=4):
    """Stream a URL to disk, returning (bytes, sha256, http_headers)."""
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            started = time.time()
            digest = hashlib.sha256()
            total = 0
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
                headers = dict(resp.headers)
                while True:
                    buf = resp.read(1 << 20)
                    if not buf:
                        break
                    out.write(buf)
                    digest.update(buf)
                    total += len(buf)
                    if total % (32 << 20) < (1 << 20):
                        rate = total / max(time.time() - started, 1e-3) / 1e6
                        _log(f"  {total / 1e6:8.1f} MB  {rate:5.1f} MB/s")
            _log(f"  done {total / 1e6:.1f} MB in {time.time() - started:.0f}s")
            return total, digest.hexdigest(), headers
        except Exception as exc:                                  # noqa: BLE001
            last = exc
            _log(f"  attempt {attempt}/{retries} failed: {exc}")
            if attempt < retries:
                time.sleep(5 * attempt)
    raise RuntimeError(f"could not download {url}: {last}")


def _osmium(args):
    cmd = ["osmium"] + args
    _log("  $ " + " ".join(cmd))
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.stderr.write(res.stdout + res.stderr)
        raise RuntimeError(f"osmium failed: {' '.join(cmd)}")
    return res.stdout


def _file_info(path):
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/osm")
    ap.add_argument("--cache", default="data/_cache")
    ap.add_argument("--keep-source", action="store_true",
                    help="keep the full regional extract after clipping")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs("data/provenance", exist_ok=True)

    if shutil.which("osmium") is None:
        raise SystemExit("osmium-tool is not installed (apt-get install -y osmium-tool)")

    # --- the AOI polygon, as an osmium extract config ----------------------
    poly_path = os.path.join(args.cache, "aoi.geojson")
    aoi.write_geojson(poly_path)
    cfg_path = os.path.join(args.cache, "extract.json")
    ring = [list(p) for p in aoi.AOI_POLYGON] + [list(aoi.AOI_POLYGON[0])]
    with open(cfg_path, "w", encoding="utf-8") as fh:
        json.dump({"extracts": [{
            "output": "mumbai-aoi.osm.pbf",
            "output_format": "pbf",
            "description": aoi.AOI_NAME,
            "polygon": [ring],
        }]}, fh, indent=1)

    # --- download ----------------------------------------------------------
    src = os.path.join(args.cache, "region-latest.osm.pbf")
    url = PRIMARY
    _log(f"downloading {url}")
    try:
        size, sha, headers = _download(url, src)
    except RuntimeError as exc:
        _log(f"primary source failed ({exc}); trying fallback")
        url = FALLBACK
        size, sha, headers = _download(url, src)

    # Geofabrik's published md5, when we used Geofabrik.
    published_md5 = None
    if url == PRIMARY:
        try:
            with urllib.request.urlopen(
                    urllib.request.Request(PRIMARY_MD5, headers={"User-Agent": USER_AGENT}),
                    timeout=60) as resp:
                published_md5 = resp.read().decode().split()[0]
            md5 = hashlib.md5()
            with open(src, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    md5.update(chunk)
            if md5.hexdigest() != published_md5:
                raise SystemExit(
                    f"FATAL: md5 mismatch, got {md5.hexdigest()} expected {published_md5}")
            _log(f"  md5 matches Geofabrik's published checksum ({published_md5})")
        except SystemExit:
            raise
        except Exception as exc:                                  # noqa: BLE001
            _log(f"  could not verify published md5: {exc}")

    # --- the extract's own replication timestamp ---------------------------
    header = _osmium(["fileinfo", "-e", "-g", "header.option.osmosis_replication_timestamp", src])
    replication = header.strip() or "unknown"
    _log(f"  OSM data timestamp: {replication}")

    # --- clip to the AOI ---------------------------------------------------
    _log("clipping to the AOI polygon")
    _osmium(["extract", "--config", cfg_path, "--directory", args.cache,
             "--overwrite", "--strategy", "complete_ways", src])
    clipped = os.path.join(args.cache, "mumbai-aoi.osm.pbf")

    final = os.path.join(args.out, "mumbai-aoi.osm.pbf")
    shutil.move(clipped, final)
    clip_size, clip_sha = _file_info(final)
    _log(f"  clipped extract {clip_size / 1e6:.1f} MB")

    info = _osmium(["fileinfo", "-e", "-j", final])
    counts = json.loads(info).get("data", {}).get("count", {})
    _log(f"  nodes={counts.get('nodes')} ways={counts.get('ways')} "
         f"relations={counts.get('relations')}")

    prov = {
        "dataset": "OpenStreetMap",
        "licence": "Open Database License (ODbL) v1.0",
        "licence_url": "https://opendatacommons.org/licenses/odbl/1-0/",
        "attribution": "\u00a9 OpenStreetMap contributors",
        "copyright_url": "https://www.openstreetmap.org/copyright",
        "source_url": url,
        "source_bytes": size,
        "source_sha256": sha,
        "source_md5_published": published_md5,
        "osm_replication_timestamp": replication,
        "retrieved_utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "http_last_modified": headers.get("Last-Modified"),
        "clip": {
            "tool": "osmium-tool",
            "tool_version": subprocess.run(["osmium", "--version"], capture_output=True,
                                           text=True).stdout.splitlines()[0],
            "strategy": "complete_ways",
            "aoi_name": aoi.AOI_NAME,
            "aoi_version": aoi.AOI_VERSION,
            "aoi_vertices": len(aoi.AOI_POLYGON),
            "output": final,
            "output_bytes": clip_size,
            "output_sha256": clip_sha,
            "counts": counts,
        },
    }
    with open("data/provenance/osm.json", "w", encoding="utf-8") as fh:
        json.dump(prov, fh, indent=1)
    _log("wrote data/provenance/osm.json")

    if not args.keep_source:
        os.remove(src)

    if clip_size < 5_000_000:
        raise SystemExit(f"FATAL: clipped extract is only {clip_size} bytes; "
                         "a real Mumbai extract is tens of megabytes")
    _log("OK")


if __name__ == "__main__":
    main()
