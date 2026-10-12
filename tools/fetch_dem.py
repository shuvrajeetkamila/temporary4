#!/usr/bin/env python3
"""
Stage 2 of the world pipeline: real elevation for the AOI.

Source is the Copernicus DEM GLO-30, the 30 m global surface model ESA
publishes openly on AWS with no credentials required. Four 1-degree tiles cover
the AOI: N18/N19 x E072/E073.

GLO-30 is a *surface* model: in dense Mumbai its returns sit on rooftops and
tree canopy, not on the street. Driving on that would be like driving over the
buildings. So the raw DSM is converted to an approximate bare-earth DTM by grey
morphological opening with a disk a little wider than a city block, which
deletes positive features narrower than the disk while leaving the kilometre
scale relief of the Salsette hills, Trombay and the SGNP ridge untouched. Both
surfaces are kept: the DTM drives terrain and physics, and DSM minus DTM gives a
free, real, per-location estimate of how tall the built-up canopy is, which the
building compiler uses when OSM has no height tag.

Must run with internet access, so it executes in GitHub Actions.

  python3 tools/fetch_dem.py --out data/dem
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import time
import urllib.request

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aoi  # noqa: E402

BUCKET = "https://copernicus-dem-30m.s3.amazonaws.com"
USER_AGENT = ("MumbaiIslandCity-worldbuild/2.0 "
              "(+https://github.com/shuvrajeetkamila/temporary4)")

# Output grid spacing in metres. 20 m is finer than the 30 m source, which
# costs little and keeps road gradients smooth after resampling.
GRID_M = 20.0

# Disk radius for the DSM -> DTM opening, in metres. Mumbai blocks are ~90 m,
# so 120 m removes buildings and keeps terrain.
OPENING_RADIUS_M = 120.0


def _log(m):
    print(f"[fetch_dem] {m}", flush=True)


def tile_names():
    minlon, minlat, maxlon, maxlat = aoi.AOI_BOUNDS
    names = []
    for lat in range(int(np.floor(minlat)), int(np.floor(maxlat)) + 1):
        for lon in range(int(np.floor(minlon)), int(np.floor(maxlon)) + 1):
            ns = "N" if lat >= 0 else "S"
            ew = "E" if lon >= 0 else "W"
            names.append(f"Copernicus_DSM_COG_10_{ns}{abs(lat):02d}_00_"
                         f"{ew}{abs(lon):03d}_00_DEM")
    return names


def _download(url, dest, retries=4):
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            digest = hashlib.sha256()
            total = 0
            with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as out:
                while True:
                    buf = resp.read(1 << 20)
                    if not buf:
                        break
                    out.write(buf)
                    digest.update(buf)
                    total += len(buf)
            return total, digest.hexdigest()
        except Exception as exc:                                  # noqa: BLE001
            last = exc
            _log(f"  attempt {attempt}/{retries} failed: {exc}")
            if attempt < retries:
                time.sleep(4 * attempt)
    raise RuntimeError(f"could not download {url}: {last}")


def disk(radius_cells):
    r = int(np.ceil(radius_cells))
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return (x * x + y * y) <= radius_cells * radius_cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/dem")
    ap.add_argument("--cache", default="data/_cache/dem")
    args = ap.parse_args()

    import rasterio
    from rasterio.merge import merge as rio_merge
    from scipy import ndimage

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs("data/provenance", exist_ok=True)

    # --- download the 1-degree tiles ---------------------------------------
    tiles, tile_prov = [], []
    for name in tile_names():
        url = f"{BUCKET}/{name}/{name}.tif"
        dest = os.path.join(args.cache, f"{name}.tif")
        if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
            _log(f"cached {name}")
            size = os.path.getsize(dest)
            h = hashlib.sha256()
            with open(dest, "rb") as fh:
                for c in iter(lambda: fh.read(1 << 20), b""):
                    h.update(c)
            sha = h.hexdigest()
        else:
            _log(f"downloading {name}")
            size, sha = _download(url, dest)
            _log(f"  {size / 1e6:.1f} MB")
        tiles.append(dest)
        tile_prov.append({"tile": name, "url": url, "bytes": size, "sha256": sha})

    # --- mosaic -------------------------------------------------------------
    srcs = [rasterio.open(t) for t in tiles]
    mosaic, transform = rio_merge(srcs)
    band = mosaic[0].astype(np.float32)
    nodata = srcs[0].nodata
    _log(f"mosaic {band.shape[1]} x {band.shape[0]} px, "
         f"pixel {abs(transform.a) * 3600:.2f} arcsec")
    for s in srcs:
        s.close()

    if nodata is not None:
        band = np.where(band == nodata, 0.0, band)
    # GLO-30 encodes ocean as exactly 0.0; keep it, the coastline comes from OSM.
    band = np.clip(band, -50.0, 2000.0)

    # --- resample onto the game's metre grid --------------------------------
    minx, minz, maxx, maxz = aoi.aoi_bounds_xz()
    # Snap outward to whole grid cells.
    minx = np.floor(minx / GRID_M) * GRID_M
    minz = np.floor(minz / GRID_M) * GRID_M
    maxx = np.ceil(maxx / GRID_M) * GRID_M
    maxz = np.ceil(maxz / GRID_M) * GRID_M
    nx = int(round((maxx - minx) / GRID_M)) + 1
    nz = int(round((maxz - minz) / GRID_M)) + 1
    _log(f"target grid {nx} x {nz} at {GRID_M:.0f} m "
         f"({nx * nz / 1e6:.2f} M samples)")

    gx = minx + GRID_M * np.arange(nx)
    gz = minz + GRID_M * np.arange(nz)
    XX, ZZ = np.meshgrid(gx, gz)

    # Inverse projection is a 4-step Newton solve on the meridian arc; vectorise
    # it rather than calling aoi.to_latlon() 9 million times.
    target = aoi._M0 - ZZ
    lat = np.degrees(target / (aoi.WGS84_A * (1.0 - aoi.WGS84_E2 / 4.0)))
    for _ in range(5):
        rl = np.radians(lat)
        e2 = aoi.WGS84_E2
        c0 = 1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256
        c2 = 3 / 8 * (e2 + e2 ** 2 / 4 + 15 * e2 ** 3 / 128)
        c4 = 15 / 256 * (e2 ** 2 + 3 * e2 ** 3 / 4)
        c6 = 35 * e2 ** 3 / 3072
        arc = aoi.WGS84_A * (c0 * rl - c2 * np.sin(2 * rl)
                             + c4 * np.sin(4 * rl) - c6 * np.sin(6 * rl))
        s = np.sin(rl)
        m = aoi.WGS84_A * (1 - e2) / (1 - e2 * s * s) ** 1.5
        lat -= np.degrees((arc - target) / m)
    rl = np.radians(lat)
    nprime = aoi.WGS84_A / np.sqrt(1 - aoi.WGS84_E2 * np.sin(rl) ** 2)
    lon = aoi.ORIGIN_LON + np.degrees(XX / (nprime * np.cos(rl)))

    inv = ~transform
    cols, rows = inv * (lon, lat)
    # Bilinear sample of the mosaic.
    r0 = np.clip(np.floor(rows).astype(np.int32), 0, band.shape[0] - 2)
    c0i = np.clip(np.floor(cols).astype(np.int32), 0, band.shape[1] - 2)
    fr = np.clip(rows - r0, 0, 1).astype(np.float32)
    fc = np.clip(cols - c0i, 0, 1).astype(np.float32)
    dsm = ((band[r0, c0i] * (1 - fc) + band[r0, c0i + 1] * fc) * (1 - fr)
           + (band[r0 + 1, c0i] * (1 - fc) + band[r0 + 1, c0i + 1] * fc) * fr)
    dsm = dsm.astype(np.float32)
    del XX, ZZ, lat, lon, rows, cols, r0, c0i, fr, fc, band, mosaic

    # --- DSM -> approximate DTM --------------------------------------------
    rad = OPENING_RADIUS_M / GRID_M
    fp = disk(rad)
    _log(f"grey opening with a {OPENING_RADIUS_M:.0f} m disk "
         f"({fp.shape[0]}x{fp.shape[1]} cells) to strip the built canopy")
    t0 = time.time()
    opened = ndimage.grey_opening(dsm, footprint=fp)
    # Opening can only lower the surface; smooth the result so block edges do
    # not become steps in the road surface.
    dtm = ndimage.uniform_filter(opened, size=3).astype(np.float32)
    dtm = np.minimum(dtm, dsm)
    _log(f"  {time.time() - t0:.1f}s")

    canopy = np.clip(dsm - dtm, 0.0, 300.0).astype(np.float32)

    _log(f"DTM range {dtm.min():.1f} .. {dtm.max():.1f} m; "
         f"canopy p50={np.percentile(canopy, 50):.1f} "
         f"p95={np.percentile(canopy, 95):.1f} max={canopy.max():.1f} m")

    # --- store as int16 decimetres -----------------------------------------
    dtm_dm = np.rint(np.clip(dtm, -100, 3200) * 10.0).astype(np.int16)
    can_dm = np.rint(np.clip(canopy, 0, 3200) * 10.0).astype(np.int16)
    np.savez_compressed(os.path.join(args.out, "elevation.npz"),
                        dtm_dm=dtm_dm, canopy_dm=can_dm,
                        min_x=np.float64(minx), min_z=np.float64(minz),
                        grid_m=np.float64(GRID_M),
                        nx=np.int32(nx), nz=np.int32(nz))
    out_path = os.path.join(args.out, "elevation.npz")
    _log(f"wrote {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB)")

    prov = {
        "dataset": "Copernicus DEM GLO-30 (COG)",
        "producer": "European Space Agency / Airbus Defence and Space",
        "licence": "Free, full and open — ESA Copernicus DEM licence",
        "licence_url": ("https://spacedata.copernicus.eu/documents/20123/121286/"
                        "CSCDA_ESA_Mission-specific+Annex.pdf"),
        "attribution": "\u00a9 DLR e.V. 2010-2014 / \u00a9 Airbus Defence and Space GmbH "
                       "2014-2018, provided under COPERNICUS by the European Union and "
                       "ESA, all rights reserved",
        "access": "public AWS S3 bucket copernicus-dem-30m, no credentials",
        "retrieved_utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "tiles": tile_prov,
        "processing": {
            "mosaic": "rasterio.merge",
            "resample": "bilinear onto the local ENU metre grid",
            "grid_m": GRID_M,
            "nx": nx, "nz": nz, "min_x": minx, "min_z": minz,
            "dsm_to_dtm": (f"grey morphological opening, disk radius "
                           f"{OPENING_RADIUS_M:.0f} m, then 3-cell mean, "
                           f"clamped not to exceed the DSM"),
            "canopy": "DSM minus DTM, used as a fallback built-height estimate",
            "storage": "int16 decimetres",
        },
        "statistics": {
            "dtm_min_m": float(dtm.min()), "dtm_max_m": float(dtm.max()),
            "dtm_mean_m": float(dtm.mean()),
            "canopy_p95_m": float(np.percentile(canopy, 95)),
            "canopy_max_m": float(canopy.max()),
        },
    }
    with open("data/provenance/dem.json", "w", encoding="utf-8") as fh:
        json.dump(prov, fh, indent=1)
    _log("wrote data/provenance/dem.json")

    # Sanity: Salsette's high ground really does exist.
    if dtm.max() < 200.0:
        raise SystemExit(f"FATAL: DTM maximum is only {dtm.max():.0f} m; the SGNP "
                         "ridge rises above 450 m, so the DEM did not load correctly")
    _log("OK")


if __name__ == "__main__":
    main()
