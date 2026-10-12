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

# Geofabrik's region tree is not guessable: India is split into zones, and
# whether a given state has its own extract changes over time. Rather than
# hard-coding a path that 404s the day it is reorganised, the fetcher reads the
# published index and picks the smallest extract that still contains the whole
# AOI, preferring these in order.
GEOFABRIK_INDEX = "https://download.geofabrik.de/asia/india.html"
GEOFABRIK_BASE = "https://download.geofabrik.de/asia/india/"
PREFERRED = ["maharashtra", "western-zone", "west-zone"]
# Whole-country extract. Always exists, about 1.3 GB, used only if the index
# yields nothing usable.
COUNTRY = "https://download.geofabrik.de/asia/india-latest.osm.pbf"

USER_AGENT = ("MumbaiIslandCity-worldbuild/2.0 "
              "(+https://github.com/shuvrajeetkamila/temporary4; offline game world build)")


def _get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _probe(url):
    """HEAD a URL; return its size in bytes, or None if it is not there."""
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            return int(resp.headers.get("Content-Length") or 0)
    except Exception:                                             # noqa: BLE001
        return None


def choose_source():
    """
    Work out which extract to download by reading Geofabrik's own index.

    Returns (url, why). Every candidate considered is logged, so when this
    breaks again the next person can see exactly what the server offered.
    """
    candidates = []
    try:
        html = _get(GEOFABRIK_INDEX).decode("utf-8", "replace")
        names = sorted(set(re.findall(r'href="([a-z0-9\-]+)-latest\.osm\.pbf"', html)))
        _log(f"Geofabrik lists {len(names)} India sub-regions: {', '.join(names)}")
        for want in PREFERRED:
            if want in names:
                candidates.append(GEOFABRIK_BASE + want + "-latest.osm.pbf")
    except Exception as exc:                                      # noqa: BLE001
        _log(f"could not read the Geofabrik index: {exc}")

    # Try the preferred names even if the index could not be read.
    for want in PREFERRED:
        u = GEOFABRIK_BASE + want + "-latest.osm.pbf"
        if u not in candidates:
            candidates.append(u)
    candidates.append(COUNTRY)

    for u in candidates:
        size = _probe(u)
        if size is None:
            _log(f"  not available: {u}")
            continue
        _log(f"  available ({size / 1e6:.0f} MB): {u}")
        return u, f"first available of {len(candidates)} candidates"
    raise SystemExit("FATAL: no OpenStreetMap extract could be located. "
                     "Candidates tried:\n  " + "\n  ".join(candidates))


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
        raise RuntimeError(f"osmium exit {res.returncode}: {' '.join(cmd)}\n"
                           f"--- stdout ---\n{res.stdout}\n"
                           f"--- stderr ---\n{res.stderr}")
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
    url, why = choose_source()
    _log(f"downloading {url}  ({why})")
    size, sha, headers = _download(url, src)

    # Geofabrik publishes an md5 beside every extract. Checking it turns "the
    # download finished" into "the download is the file they published".
    published_md5 = None
    if "geofabrik.de" in url:
        try:
            published_md5 = _get(url + ".md5").decode().split()[0]
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
    # Ask for the whole header as JSON rather than one key: a missing key makes
    # `osmium fileinfo -g` exit non-zero, and provenance metadata going missing
    # is not a reason to fail the build.
    replication = "unknown"
    try:
        hdr = json.loads(_osmium(["fileinfo", "-j", src]))
        opts = hdr.get("header", {}).get("options", {}) or {}
        replication = (opts.get("osmosis_replication_timestamp")
                       or opts.get("timestamp") or "unknown")
    except Exception as exc:                                      # noqa: BLE001
        _log(f"  could not read the replication timestamp: {exc}")
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

    counts = {}
    try:
        info = _osmium(["fileinfo", "-e", "-j", final])
        counts = json.loads(info).get("data", {}).get("count", {})
    except Exception as exc:                                      # noqa: BLE001
        _log(f"  could not count objects: {exc}")
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
