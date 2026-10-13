#!/usr/bin/env python3
"""
Stage 4: turn the extracted OSM layers and the Copernicus elevation grid into
the packaged world the game ships with.

Two kinds of output:

  rasters   a global 20 m grid of ground elevation, land cover and a land/sea
            mask. Small enough to hold resident, so distant terrain and the
            minimap never need to wait for streaming.

  chunks    1024 m tiles of real vector geometry — OSM building footprints and
            road centrelines with their real widths, lane counts, one-way
            directions, bridge/tunnel layers and names. Meshes are built at
            runtime from these; baking triangles would multiply the size for
            no gain.

Building heights come from OSM ``height`` first, then ``building:levels``, and
only if OSM says nothing at all from the Copernicus DSM-minus-DTM canopy at the
footprint. Which of the three was used is recorded per building and reported in
aggregate, so nobody has to guess how much of the skyline is measured.

  python3 tools/compile_world.py --layers data/layers --dem data/dem --out <dir>
  python3 tools/compile_world.py --selftest --out /tmp/w   # no real data needed
"""

import argparse
import hashlib
import json
import math
import os
import struct
import sys
import time
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aoi                                                        # noqa: E402
import world_format as wf                                         # noqa: E402
from extract_osm import (LANDUSE_CLASSES, BUILDING_CLASSES,        # noqa: E402
                         ROAD_CLASSES, LANDUSE_ID)

GRID_M = 20.0           # raster spacing, matches tools/fetch_dem.py
CHUNK_M = 1024.0        # vector chunk size
QUANT = 0.05            # chunk-local coordinate quantum, metres
SEA_LEVEL = 0.0

# Land cover ids that are water for physics purposes.
WATER_COVERS = {LANDUSE_ID["water"]}
# Cover id used for open sea outside every polygon.
SEA_COVER = LANDUSE_ID["water"]
# Default cover for land with nothing mapped on it.
DEFAULT_LAND_COVER = LANDUSE_ID["scrub"]

# Rasterisation order: later entries paint over earlier ones.
COVER_PRIORITY = [
    "rock", "sand", "beach", "farmland", "scrub", "forest", "grass", "park",
    "cemetery", "pitch", "quarry", "construction", "railway_land",
    "airport_land", "port", "military", "industrial", "retail", "commercial",
    "residential", "parking", "wetland", "mangrove", "water",
]

MIN_BUILDING_AREA = 9.0      # m^2; below this it is map noise, not a building
MAX_BUILDING_PTS = 64

# Cell size for the neighbourhood height prior, metres.
HEIGHT_PRIOR_M = 300.0

# Fallback heights per building class: (default, min, max) metres.
#
# Copernicus GLO-30 turned out to be useless for this. At 30 m posting its
# surface over Nariman Point reads about two metres above the local ground,
# when the towers there are over a hundred - the product is too smoothed to
# resolve individual buildings in a dense city. Rather than dress that up, the
# compiler uses it for terrain only, and heights for the 84% of footprints OSM
# does not tag come from the median of tagged buildings in the same 300 m
# neighbourhood, falling back to these class priors where no neighbour is
# tagged either. Which method produced each building is counted and reported.
CLASS_HEIGHT = {
    "generic": (9.0, 3.0, 120.0), "residential": (14.0, 3.0, 150.0),
    "apartments": (18.0, 6.0, 180.0), "house": (6.5, 3.0, 20.0),
    "commercial": (14.0, 4.0, 160.0), "office": (20.0, 4.0, 220.0),
    "retail": (9.0, 3.5, 60.0), "industrial": (10.0, 4.0, 60.0),
    "warehouse": (9.0, 4.0, 45.0), "civic": (12.0, 4.0, 90.0),
    "school": (10.0, 3.5, 40.0), "hospital": (16.0, 4.0, 90.0),
    "religious": (9.0, 3.0, 60.0), "transport": (9.0, 3.0, 60.0),
    "hut": (3.0, 2.4, 6.0), "shed": (3.2, 2.4, 8.0),
    "garage": (3.2, 2.4, 9.0), "construction": (12.0, 3.0, 120.0),
    "ruins": (5.0, 2.4, 20.0), "stadium": (20.0, 6.0, 60.0),
    "hotel": (22.0, 5.0, 160.0), "tower": (45.0, 6.0, 250.0),
}


def _log(m):
    print(f"[compile] {m}", flush=True)


# --------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------

def ring_area(pts):
    x = pts[:, 0]
    z = pts[:, 1]
    return 0.5 * float(np.sum(x * np.roll(z, -1) - np.roll(x, -1) * z))


def simplify(pts, tol):
    """Iterative Douglas-Peucker. Keeps rings closed."""
    n = len(pts)
    if n <= 4:
        return pts
    keep = np.zeros(n, dtype=bool)
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    t2 = tol * tol
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        ax, az = pts[a]
        bx, bz = pts[b]
        dx, dz = bx - ax, bz - az
        seg = dx * dx + dz * dz
        sec = pts[a + 1:b]
        if seg < 1e-12:
            d2 = (sec[:, 0] - ax) ** 2 + (sec[:, 1] - az) ** 2
        else:
            t = np.clip(((sec[:, 0] - ax) * dx + (sec[:, 1] - az) * dz) / seg, 0, 1)
            px = ax + t * dx
            pz = az + t * dz
            d2 = (sec[:, 0] - px) ** 2 + (sec[:, 1] - pz) ** 2
        i = int(np.argmax(d2))
        if d2[i] > t2:
            k = a + 1 + i
            keep[k] = True
            stack.append((a, k))
            stack.append((k, b))
    return pts[keep]


def split_polyline(pts, chunk):
    """Yield (chunk_index_xz, sub_polyline) so no run crosses a chunk border."""
    cur = [pts[0]]
    cx, cz = chunk(pts[0])
    for i in range(1, len(pts)):
        nx_, nz_ = chunk(pts[i])
        if (nx_, nz_) != (cx, cz):
            cur.append(pts[i])
            if len(cur) >= 2:
                yield (cx, cz), np.asarray(cur)
            cur = [pts[i - 1], pts[i]]
            cx, cz = nx_, nz_
        else:
            cur.append(pts[i])
    if len(cur) >= 2:
        yield (cx, cz), np.asarray(cur)


# --------------------------------------------------------------------------
# Self-test fixture: structurally valid, obviously synthetic layers.
# --------------------------------------------------------------------------

def synth_layers():
    _log("SELF TEST: fabricating structurally valid layers (not real data)")
    rng = np.random.default_rng(7)
    minx, minz, maxx, maxz = aoi.aoi_bounds_xz()
    minx = math.floor(minx / GRID_M) * GRID_M
    minz = math.floor(minz / GRID_M) * GRID_M
    maxx = math.ceil(maxx / GRID_M) * GRID_M
    maxz = math.ceil(maxz / GRID_M) * GRID_M
    nx = int(round((maxx - minx) / GRID_M)) + 1
    nz = int(round((maxz - minz) / GRID_M)) + 1

    # A plausible island: an ellipse covering the checkpoints that expect land.
    land_pts = np.array([aoi.to_xz(lat, lon) for (_g, _k, _l, lat, lon, e)
                         in aoi.CHECKPOINTS if e == "land"])
    cx, cz = land_pts.mean(axis=0)
    rx = (land_pts[:, 0].max() - land_pts[:, 0].min()) * 0.72
    rz = (land_pts[:, 1].max() - land_pts[:, 1].min()) * 0.62
    th = np.linspace(0, 2 * math.pi, 240, endpoint=False)
    ring = np.stack([cx + rx * np.cos(th), cz + rz * np.sin(th)], axis=1)

    land_xy = ring
    land_off = np.array([0, len(ring)], dtype=np.int64)
    land_role = np.array([0], dtype=np.int8)

    # Buildings on a grid inside the island.
    bxs, bzs, b_off, b_attr = [], [], [0], []
    bld = []
    for gx in np.arange(cx - rx * 0.8, cx + rx * 0.8, 260.0):
        for gz in np.arange(cz - rz * 0.8, cz + rz * 0.8, 260.0):
            if ((gx - cx) / rx) ** 2 + ((gz - cz) / rz) ** 2 > 0.85:
                continue
            w, d = rng.uniform(12, 34), rng.uniform(12, 34)
            r = np.array([[gx, gz], [gx + w, gz], [gx + w, gz + d], [gx, gz + d],
                          [gx, gz]])
            bld.append(r)
            b_attr.append((rng.integers(0, len(BUILDING_CLASSES)),
                           rng.uniform(6, 90), rng.integers(0, 3), 0.0, 0, len(r)))
    for r in bld:
        bxs.extend(r.tolist())
        b_off.append(len(bxs))

    # Roads on the same lattice.
    r_xy, r_off, r_attr, names = [], [0], [], []
    for gx in np.arange(cx - rx * 0.8, cx + rx * 0.8, 260.0):
        line = np.stack([np.full(60, gx), np.linspace(cz - rz * 0.8, cz + rz * 0.8, 60)],
                        axis=1)
        r_xy.extend(line.tolist())
        r_off.append(len(r_xy))
        r_attr.append((2, 4, 0, 14.0, 60, 0, 0, 0, 0, len(line)))
        names.append("Synthetic Marg")
    for gz in np.arange(cz - rz * 0.8, cz + rz * 0.8, 260.0):
        line = np.stack([np.linspace(cx - rx * 0.8, cx + rx * 0.8, 60), np.full(60, gz)],
                        axis=1)
        r_xy.extend(line.tolist())
        r_off.append(len(r_xy))
        r_attr.append((5, 2, 0, 8.0, 30, 0, 0, 0, 0, len(line)))
        names.append("Synthetic Cross Road")

    lay = {
        "road_xy": np.asarray(r_xy, np.float32), "road_off": np.asarray(r_off, np.int64),
        "road_attr": np.asarray(r_attr, np.float32),
        "bld_xy": np.asarray(bxs, np.float32), "bld_off": np.asarray(b_off, np.int64),
        "bld_attr": np.asarray(b_attr, np.float32),
        "lu_xy": np.zeros((0, 2), np.float32), "lu_off": np.array([0], np.int64),
        "lu_attr": np.zeros((0, 2), np.float32),
        "water_xy": np.zeros((0, 2), np.float32), "water_off": np.array([0], np.int64),
        "water_attr": np.zeros((0, 3), np.float32),
        "land_xy": land_xy.astype(np.float32), "land_off": land_off,
        "land_role": land_role,
    }
    # Hilly synthetic terrain so slope code has something to chew on.
    gxv = (np.arange(nx) * GRID_M + minx)[None, :]
    gzv = (np.arange(nz) * GRID_M + minz)[:, None]
    dtm = (40.0 * np.exp(-(((gxv - cx) / 7000.0) ** 2 + ((gzv - cz) / 9000.0) ** 2))
           + 12.0 * np.sin(gxv / 2300.0) * np.cos(gzv / 2700.0) + 14.0)
    dem = {"dtm_dm": np.rint(dtm * 10).astype(np.int16),
           "canopy_dm": np.rint(np.clip(10.0 + 6.0 * np.sin(gxv / 900.0), 0, 200)
                                * 10 * np.ones_like(dtm)).astype(np.int16),
           "min_x": np.float64(minx), "min_z": np.float64(minz),
           "grid_m": np.float64(GRID_M), "nx": np.int32(nx), "nz": np.int32(nz)}
    return lay, dem, names, []


# --------------------------------------------------------------------------
# Rasters
# --------------------------------------------------------------------------

def build_rasters(lay, dem):
    from rasterio.features import rasterize
    from rasterio.transform import Affine

    nx, nz = int(dem["nx"]), int(dem["nz"])
    minx, minz = float(dem["min_x"]), float(dem["min_z"])
    transform = Affine(GRID_M, 0.0, minx, 0.0, GRID_M, minz)
    _log(f"rasters {nx} x {nz} @ {GRID_M:.0f} m "
         f"({nx * nz / 1e6:.2f} M cells, {nx * GRID_M / 1000:.1f} x "
         f"{nz * GRID_M / 1000:.1f} km)")

    def rings_of(xy, off, role=None):
        out = []
        for i in range(len(off) - 1):
            r = xy[off[i]:off[i + 1]]
            if len(r) >= 4:
                out.append((r, 0 if role is None else int(role[i])))
        return out

    # --- land / sea from the OSM coastline ---------------------------------
    land_rings = rings_of(lay["land_xy"], lay["land_off"], lay["land_role"])
    shapes = []
    for r, kind in land_rings:
        coords = [(float(a), float(b)) for a, b in r]
        if coords[0] != coords[-1]:
            coords.append(coords[0])
        if len(coords) >= 4:
            shapes.append(({"type": "Polygon", "coordinates": [coords]},
                           1 if kind == 0 else 0))
    _log(f"  rasterising {len(shapes)} coastline rings")
    outer = [s for s in shapes if s[1] == 1]
    holes = [s for s in shapes if s[1] == 0]
    landmask = rasterize(outer, out_shape=(nz, nx), transform=transform,
                         fill=0, dtype="uint8", all_touched=False)
    if holes:
        hm = rasterize([(g, 1) for g, _ in holes], out_shape=(nz, nx),
                       transform=transform, fill=0, dtype="uint8")
        landmask = np.where(hm > 0, 0, landmask)
    land_frac = float(landmask.mean())
    _log(f"  land fraction {land_frac * 100:.1f}% "
         f"({land_frac * nx * nz * GRID_M ** 2 / 1e6:.0f} km^2)")

    # --- land cover ---------------------------------------------------------
    cover = np.where(landmask > 0, DEFAULT_LAND_COVER, SEA_COVER).astype(np.uint8)
    by_class = {}
    for name, xy, off, attr in (("lu", lay["lu_xy"], lay["lu_off"], lay["lu_attr"]),
                                ("w", lay["water_xy"], lay["water_off"],
                                 lay["water_attr"])):
        for i in range(len(off) - 1):
            r = xy[off[i]:off[i + 1]]
            if len(r) < 4:
                continue
            cid = int(attr[i][0])
            by_class.setdefault(cid, []).append(r)
    painted = 0
    for cname in COVER_PRIORITY:
        cid = LANDUSE_ID[cname]
        rings = by_class.get(cid, [])
        if not rings:
            continue
        shp = []
        for r in rings:
            coords = [(float(a), float(b)) for a, b in r]
            if coords[0] != coords[-1]:
                coords.append(coords[0])
            if len(coords) >= 4:
                shp.append(({"type": "Polygon", "coordinates": [coords]}, 1))
        if not shp:
            continue
        m = rasterize(shp, out_shape=(nz, nx), transform=transform, fill=0,
                      dtype="uint8")
        cover = np.where(m > 0, np.uint8(cid), cover)
        painted += len(shp)
    _log(f"  painted {painted} land-cover polygons in {len(COVER_PRIORITY)} passes")

    # Open sea stays sea even if a landuse polygon overhangs the coast.
    cover = np.where(landmask == 0, np.uint8(SEA_COVER), cover)

    # Inland water is water too. The OSM coastline only separates the sea from
    # the land; it says nothing about Powai Lake, the Vihar and Tulsi
    # reservoirs, the Mithi river or the tidal creeks mapped as areas rather
    # than coastline. Without this a boat cannot float on any of them and the
    # car happily drives across the reservoirs.
    inland = (cover == np.uint8(LANDUSE_ID["water"])) & (landmask > 0)
    n_inland = int(inland.sum())
    landmask = np.where(inland, np.uint8(0), landmask)
    _log(f"  inland water: {n_inland} cells "
         f"({n_inland * GRID_M ** 2 / 1e6:.1f} km^2) removed from the land mask")
    land_frac = float(landmask.mean())

    # --- elevation ----------------------------------------------------------
    dtm = np.asarray(dem["dtm_dm"], dtype=np.int16).reshape(nz, nx)
    # The sea is flat at chart datum; the DEM's noisy near-zero water returns
    # would otherwise make the harbour look like a ploughed field.
    dtm = np.where(landmask == 0, np.int16(0), dtm)
    # Keep genuine land at or above the waterline so the shore does not flood.
    dtm = np.where((landmask > 0) & (dtm < 3), np.int16(3), dtm)
    canopy = np.asarray(dem["canopy_dm"], dtype=np.int16).reshape(nz, nx)

    stats = {
        "nx": nx, "nz": nz, "grid_m": GRID_M, "min_x": minx, "min_z": minz,
        "land_fraction": round(land_frac, 5),
        "inland_water_km2": round(n_inland * GRID_M ** 2 / 1e6, 1),
        "land_km2": round(land_frac * nx * nz * GRID_M ** 2 / 1e6, 1),
        "elev_min_m": round(float(dtm.min()) / 10, 1),
        "elev_max_m": round(float(dtm.max()) / 10, 1),
        "cover_histogram": {LANDUSE_CLASSES[i]: int(c) for i, c in
                            zip(*np.unique(cover, return_counts=True))},
    }
    return landmask, cover, dtm, canopy, stats


def sample_grid(grid, minx, minz, x, z, nx, nz):
    """Nearest-cell lookup used for building heights."""
    i = np.clip(((x - minx) / GRID_M).astype(np.int32), 0, nx - 1)
    j = np.clip(((z - minz) / GRID_M).astype(np.int32), 0, nz - 1)
    return grid[j, i]


# --------------------------------------------------------------------------
# Chunks
# --------------------------------------------------------------------------

def build_chunks(lay, canopy, dtm, landmask, raster_stats, names):
    nx, nz = raster_stats["nx"], raster_stats["nz"]
    minx, minz = raster_stats["min_x"], raster_stats["min_z"]
    ox, oz, maxx, maxz = aoi.aoi_bounds_xz()
    cminx = math.floor(ox / CHUNK_M)
    cminz = math.floor(oz / CHUNK_M)
    cnx = int(math.ceil(maxx / CHUNK_M)) - cminx + 1
    cnz = int(math.ceil(maxz / CHUNK_M)) - cminz + 1
    _log(f"chunk grid {cnx} x {cnz} = {cnx * cnz} chunks of {CHUNK_M:.0f} m")

    buildings = [[] for _ in range(cnx * cnz)]
    roads = [[] for _ in range(cnx * cnz)]

    def cidx(x, z):
        i = int(math.floor(x / CHUNK_M)) - cminx
        j = int(math.floor(z / CHUNK_M)) - cminz
        return min(max(i, 0), cnx - 1), min(max(j, 0), cnz - 1)

    # --- neighbourhood height prior -----------------------------------------
    # Median height of every OSM-tagged building, per 300 m cell. Built first
    # so the untagged majority can inherit the real character of their own
    # neighbourhood: towers around Lower Parel and BKC, low-rise in Dharavi.
    b_xy, b_off, b_attr = lay["bld_xy"], lay["bld_off"], lay["bld_attr"]
    nb = len(b_attr)
    pminx, pminz, pmaxx, pmaxz = aoi.aoi_bounds_xz()
    pnx = int((pmaxx - pminx) / HEIGHT_PRIOR_M) + 2
    pnz = int((pmaxz - pminz) / HEIGHT_PRIOR_M) + 2
    buckets = {}
    for i in range(nb):
        src = int(b_attr[i][2])
        if src == 0:
            continue
        h = float(b_attr[i][1])
        if not (2.0 < h < 400.0):
            continue
        r = b_xy[b_off[i]:b_off[i + 1]]
        if len(r) < 3:
            continue
        pi = int((float(r[:, 0].mean()) - pminx) / HEIGHT_PRIOR_M)
        pj = int((float(r[:, 1].mean()) - pminz) / HEIGHT_PRIOR_M)
        buckets.setdefault((pi, pj), []).append(h)
    prior = np.full((pnz, pnx), np.nan, dtype=np.float32)
    for (pi, pj), vals in buckets.items():
        if 0 <= pi < pnx and 0 <= pj < pnz and len(vals) >= 3:
            prior[pj, pi] = float(np.median(vals))
    filled = int(np.isfinite(prior).sum())
    _log(f"  height prior: {filled} of {pnx * pnz} cells have 3+ tagged buildings")
    # Spread the prior one ring outward so a cell with no tagged building still
    # inherits from its neighbours rather than dropping straight to the class
    # default.
    if filled:
        from scipy import ndimage as _nd
        valid = np.isfinite(prior)
        fillv = np.where(valid, prior, 0.0)
        for _ in range(2):
            sm = _nd.uniform_filter(fillv, size=5)
            cnt = _nd.uniform_filter(valid.astype(np.float32), size=5)
            grown = np.where(cnt > 0.02, sm / np.maximum(cnt, 1e-6), np.nan)
            prior = np.where(valid, prior, grown)
            valid = np.isfinite(prior)
            fillv = np.where(valid, prior, 0.0)
        _log(f"  height prior after spreading: {int(np.isfinite(prior).sum())} cells")

    def prior_at(x, z):
        pi = int((x - pminx) / HEIGHT_PRIOR_M)
        pj = int((z - pminz) / HEIGHT_PRIOR_M)
        if 0 <= pi < pnx and 0 <= pj < pnz:
            v = prior[pj, pi]
            if np.isfinite(v):
                return float(v)
        return None

    # --- buildings ----------------------------------------------------------
    src_counts = [0, 0, 0, 0]   # 0 class prior, 1 levels, 2 height, 3 neighbours
    dropped_small = 0
    kept = 0
    t0 = time.time()
    for i in range(nb):
        r = b_xy[b_off[i]:b_off[i + 1]]
        if len(r) < 4:
            continue
        if r[0][0] != r[-1][0] or r[0][1] != r[-1][1]:
            r = np.vstack([r, r[0]])
        a = abs(ring_area(r))
        if a < MIN_BUILDING_AREA:
            dropped_small += 1
            continue
        if len(r) > 8:
            r = simplify(r, 0.45)
            if len(r) > MAX_BUILDING_PTS:
                r = r[:: max(1, len(r) // MAX_BUILDING_PTS)]
                r = np.vstack([r, r[0]])
        cls, h, src, minh, roof, _n = b_attr[i]
        cls, src = int(cls), int(src)
        cx = float(r[:, 0].mean())
        cz = float(r[:, 1].mean())
        if src == 0:
            name = BUILDING_CLASSES[cls] if cls < len(BUILDING_CLASSES) else "generic"
            dflt, lo, hi = CLASS_HEIGHT.get(name, CLASS_HEIGHT["generic"])
            p = prior_at(cx, cz)
            if p is not None:
                # Weight the neighbourhood more than the class prior, but keep
                # the class bounds: a mapped shed next to a tower is still a shed.
                h = 0.68 * p + 0.32 * dflt
                src = 3
            else:
                h = dflt
            # A tiny footprint cannot be a tower whatever the neighbours do.
            cap = 5.0 + 3.1 * math.sqrt(max(a, 1.0))
            h = float(np.clip(h, lo, min(hi, cap)))
        h = float(np.clip(h, 2.4, 420.0))
        src_counts[src] += 1
        ci, cj = cidx(cx, cz)
        buildings[cj * cnx + ci].append((cls, h, float(minh), int(roof), r))
        kept += 1
        if kept % 250000 == 0:
            _log(f"  {kept // 1000}k buildings ({time.time() - t0:.0f}s)")
    _log(f"  buildings kept {kept}, dropped {dropped_small} under "
         f"{MIN_BUILDING_AREA:.0f} m^2")
    tot = max(1, sum(src_counts))
    _log(f"  height source: osm_height={src_counts[2]} ({100 * src_counts[2] / tot:.1f}%) "
         f"osm_levels={src_counts[1]} ({100 * src_counts[1] / tot:.1f}%) "
         f"neighbour_median={src_counts[3]} ({100 * src_counts[3] / tot:.1f}%) "
         f"class_default={src_counts[0]} ({100 * src_counts[0] / tot:.1f}%)")

    # --- roads --------------------------------------------------------------
    r_xy, r_off, r_attr = lay["road_xy"], lay["road_off"], lay["road_attr"]
    name_idx = {}
    name_list = []
    nr = len(r_attr)
    segs = 0
    for i in range(nr):
        p = r_xy[r_off[i]:r_off[i + 1]]
        if len(p) < 2:
            continue
        if len(p) > 3:
            p = simplify(p, 0.7)
        cls, lanes, oneway, width, speed, layer, bridge, tunnel, unpaved, _n = r_attr[i]
        nm = names[i] if i < len(names) else ""
        if nm:
            if nm not in name_idx:
                name_idx[nm] = len(name_list)
                name_list.append(nm)
            ni = name_idx[nm]
        else:
            ni = 0xFFFFFFFF
        for (ci, cj), sub in split_polyline(p, lambda q: cidx(q[0], q[1])):
            roads[cj * cnx + ci].append((int(cls), int(lanes), int(oneway),
                                         float(width), int(speed), int(layer),
                                         int(bridge), int(tunnel), int(unpaved),
                                         ni, sub))
            segs += 1
    _log(f"  road ways {nr} -> {segs} chunk-local runs, {len(name_list)} distinct names")

    # --- serialise ----------------------------------------------------------
    # Each chunk is deflated on its own and the payload layer is stored
    # verbatim, so a streaming worker inflates one tile instead of the lot.
    blob = bytearray()
    index = np.zeros((cnx * cnz, 6), dtype=np.uint32)
    # columns: byte offset, stored length, raw length, buildings, road runs, spare
    for k in range(cnx * cnz):
        bl, rl = buildings[k], roads[k]
        if not bl and not rl:
            index[k] = (0, 0, 0, 0, 0, 0)
            continue
        start = len(blob)
        cj, ci = divmod(k, cnx)
        bx = (ci + cminx) * CHUNK_M
        bz = (cj + cminz) * CHUNK_M
        out = bytearray()
        out += struct.pack("<I", len(bl))
        for (cls, h, minh, roof, r) in bl:
            q = np.rint((r[:-1] - np.array([bx, bz])) / QUANT).astype(np.int32)
            q = np.clip(q, -32768, 32767).astype("<i2")
            out += struct.pack("<BBHHH", cls, roof, int(round(h * 10)),
                               int(round(min(minh, 400.0) * 10)), len(q))
            out += q.tobytes()
        out += struct.pack("<I", len(rl))
        for (cls, lanes, oneway, width, speed, layer, bridge, tunnel,
             unpaved, ni, sub) in rl:
            q = np.rint((sub - np.array([bx, bz])) / QUANT).astype(np.int32)
            q = np.clip(q, -32768, 32767).astype("<i2")
            flags = (bridge & 1) | ((tunnel & 1) << 1) | ((unpaved & 1) << 2) \
                | (((oneway + 1) & 3) << 3)
            out += struct.pack("<BBBBHbHI", cls, min(lanes, 255), flags,
                               min(speed, 255), int(round(width * 10)),
                               max(-6, min(6, layer)), len(q), ni)
            out += q.tobytes()
        comp = zlib.compress(bytes(out), 9)
        blob += comp
        index[k] = (start, len(comp), len(out), len(bl), len(rl), 0)

    _log(f"  chunk payload {len(blob) / 1e6:.1f} MB deflated "
         f"(raw {int(index[:, 2].sum()) / 1e6:.1f} MB), "
         f"{int((index[:, 1] > 0).sum())} non-empty chunks")
    meta = {"cnx": cnx, "cnz": cnz, "cminx": cminx, "cminz": cminz,
            "chunk_m": CHUNK_M, "quant": QUANT,
            "buildings": kept, "building_runs": kept, "road_runs": segs,
            "height_src": {"osm_height": src_counts[2],
                           "osm_levels": src_counts[1],
                           "neighbour_median": src_counts[3],
                           "class_default": src_counts[0]},
            "names": len(name_list)}
    return bytes(blob), index, name_list, meta


# --------------------------------------------------------------------------
# Road graph for traffic and navigation
# --------------------------------------------------------------------------

MAX_JOIN_TURN = 75.0
"""Sharpest turn, in degrees, allowed when chaining two fragments."""

MAX_VERTEX_TURN = 100.0
"""Sharpest turn allowed to survive inside a finished route."""


def _unit(a, b):
    dx, dz = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dz)
    return None if n < 1e-9 else (dx / n, dz / n)


def _turn_deg(u, v):
    dot = max(-1.0, min(1.0, u[0] * v[0] + u[1] * v[1]))
    return math.degrees(math.acos(dot))


def _split_hairpins(chain):
    """Breaks a polyline wherever it reverses on itself."""
    out, cur = [], [chain[0]] if chain else []
    for i in range(1, len(chain)):
        cur.append(chain[i])
        if i + 1 >= len(chain):
            break
        u = _unit(chain[i - 1], chain[i])
        v = _unit(chain[i], chain[i + 1])
        if u is not None and v is not None and _turn_deg(u, v) > MAX_VERTEX_TURN:
            if len(cur) >= 2:
                out.append(cur)
            cur = [chain[i]]
    if len(cur) >= 2:
        out.append(cur)
    return out


def _clip_runs(chain, x0, z0, x1, z1):
    """Maximal runs of a polyline that lie inside a rectangle.

    Splits rather than trims: a line that leaves the area and comes back
    becomes two routes, which is right, because the part outside is not
    in the compiled world and a service cannot run through it.
    """
    runs, cur = [], []
    for p in chain:
        if x0 <= p[0] <= x1 and z0 <= p[1] <= z1:
            cur.append(p)
        else:
            if len(cur) >= 2:
                runs.append(cur)
            cur = []
    if len(cur) >= 2:
        runs.append(cur)
    return runs


def build_transit(lay):
    """Stitched rail, metro, monorail and ferry routes.

    These are deliberately NOT in the road graph: the graph is filtered to
    kind == "road" so that traffic cannot spawn in a subway tunnel or a
    taxi be sent to a destination in the middle of the harbour. But the
    alignments are real and the city needs trains running on them, so they
    get their own network.

    OSM splits railway ways at every junction, signal and bridge, which
    leaves a few thousand fragments averaging under 200 m. A train that
    terminates every 200 m is not a train, so ways of the same class are
    chained end to end wherever they share an endpoint, giving runs long
    enough to be worth riding alongside.
    """
    r_xy, r_off, r_attr = lay["road_xy"], lay["road_off"], lay["road_attr"]
    want = {i: c[5] for i, c in enumerate(ROAD_CLASSES) if c[5] in ("rail", "water")}

    # Collect fragments per class.
    frags = {}
    for i in range(len(r_off) - 1):
        cls = int(r_attr[i][0])
        if cls not in want:
            continue
        pts = r_xy[r_off[i]:r_off[i + 1]]
        if len(pts) < 2:
            continue
        frags.setdefault(cls, []).append([(float(a), float(b)) for a, b in pts])

    def key(pt):
        return (int(round(pt[0])), int(round(pt[1])))

    bx0, bz0, bx1, bz1 = aoi.aoi_bounds_xz()

    lines, classes = [], []
    for cls in sorted(frags):
        pool = frags[cls]
        # Endpoint index: which fragments start or end at each node.
        ends = {}
        for idx, f in enumerate(pool):
            ends.setdefault(key(f[0]), []).append(idx)
            ends.setdefault(key(f[-1]), []).append(idx)
        used = [False] * len(pool)

        for seed in range(len(pool)):
            if used[seed]:
                continue
            used[seed] = True
            chain = list(pool[seed])
            # Extend from both ends until nothing else connects.
            #
            # At a junction, take the straightest continuation rather than
            # whichever fragment happens to come first. Picking arbitrarily
            # runs the route out onto a siding and straight back: the first
            # version produced 92 vertices turning more than 100 degrees,
            # the sharpest a full 179, which is a route doubling back along
            # itself. Tracing the line the way a person would reads the
            # geometry correctly and keeps a service on its own line
            # through a station throat.
            for _ in range(2):
                while True:
                    tail = key(chain[-1])
                    indir = _unit(chain[-2], chain[-1]) if len(chain) >= 2 else None
                    nxt, best_turn, flip = None, MAX_JOIN_TURN, False
                    for cand in ends.get(tail, ()):
                        if used[cand]:
                            continue
                        seg = pool[cand]
                        at_start = key(seg[0]) == tail
                        if at_start:
                            out = _unit(seg[0], seg[1])
                        elif key(seg[-1]) == tail:
                            out = _unit(seg[-1], seg[-2])
                        else:
                            continue
                        if out is None:
                            continue
                        turn = 0.0 if indir is None else _turn_deg(indir, out)
                        if turn < best_turn:
                            nxt, best_turn, flip = cand, turn, not at_start
                    if nxt is None:
                        break
                    used[nxt] = True
                    seg = pool[nxt]
                    # Join on whichever end matches, dropping the shared point.
                    if not flip:
                        chain.extend(seg[1:])
                    else:
                        chain.extend(reversed(seg[:-1]))
                chain.reverse()

            # Clip to the AOI. OSM keeps whole ways that cross the
            # boundary, so a line out of Virar or across to the mainland
            # carries nodes up to a couple of kilometres past the edge of
            # the compiled world, and a train running out there would be
            # gliding over nothing. Split into the runs that are inside
            # rather than discarding the whole route.
            # Belt and braces: whatever survives, split at any vertex that
            # still doubles back. Nothing on rails turns 100 degrees on the
            # spot, and one such vertex puts two carriages in the same
            # place.
            for straight in _split_hairpins(chain):
                for piece in _clip_runs(straight, bx0, bz0, bx1, bz1):
                    length = sum(math.hypot(piece[j + 1][0] - piece[j][0],
                                            piece[j + 1][1] - piece[j][1])
                                 for j in range(len(piece) - 1))
                    # Short stubs are sidings and crossovers; nothing runs
                    # on them.
                    if length < 600.0:
                        continue
                    # 0.8 m rather than the 2 m used for roads: a train
                    # visibly cuts the corner on a chunky polyline, and
                    # the whole network still costs a tenth of a megabyte.
                    simple = simplify(np.asarray(piece, dtype="f8"), 0.8)
                    if len(simple) < 2:
                        continue
                    lines.append(np.asarray(simple, dtype="<f4"))
                    classes.append(cls)

    if lines:
        pts = np.concatenate(lines, axis=0)
        off = np.zeros(len(lines) + 1, dtype="<u4")
        run = 0
        for i, ln in enumerate(lines):
            off[i] = run
            run += len(ln)
        off[-1] = run
    else:
        pts = np.zeros((0, 2), dtype="<f4")
        off = np.zeros(1, dtype="<u4")

    by_class = {}
    total = 0.0
    for ln, cls in zip(lines, classes):
        d = float(np.sum(np.hypot(np.diff(ln[:, 0]), np.diff(ln[:, 1]))))
        total += d
        name = ROAD_CLASSES[cls][0]
        e = by_class.setdefault(name, {"routes": 0, "km": 0.0})
        e["routes"] += 1
        e["km"] = round(e["km"] + d / 1000.0, 1)

    meta = {"routes": len(lines), "km": round(total / 1000.0, 1),
            "by_class": by_class}
    _log(f"transit: {len(lines)} routes, {total / 1000:.0f} km "
         + ", ".join(f"{k} {v['routes']}" for k, v in sorted(by_class.items())))
    return (pts.astype("<f4"), off,
            np.asarray(classes, dtype="<u4"), meta)


def build_road_graph(lay, names):
    """Node-and-edge graph over drivable roads, snapped at 1 m."""
    r_xy, r_off, r_attr = lay["road_xy"], lay["road_off"], lay["road_attr"]
    drivable = {i for i, c in enumerate(ROAD_CLASSES) if c[5] == "road"}
    nodes = {}
    node_xy = []
    edges = []

    def nid(p):
        k = (int(round(p[0])), int(round(p[1])))
        v = nodes.get(k)
        if v is None:
            v = len(node_xy)
            nodes[k] = v
            node_xy.append((float(p[0]), float(p[1])))
        return v

    for i in range(len(r_attr)):
        cls = int(r_attr[i][0])
        if cls not in drivable:
            continue
        p = r_xy[r_off[i]:r_off[i + 1]]
        if len(p) < 2:
            continue
        p = simplify(p, 1.2) if len(p) > 3 else p
        lanes, oneway, width, speed = (int(r_attr[i][1]), int(r_attr[i][2]),
                                       float(r_attr[i][3]), int(r_attr[i][4]))
        prev = nid(p[0])
        for j in range(1, len(p)):
            cur = nid(p[j])
            if cur == prev:
                continue
            edges.append((prev, cur, cls, lanes, oneway, width, speed))
            prev = cur

    nxy = np.asarray(node_xy, dtype=np.float32)
    ea = np.asarray([(a, b) for (a, b, *_r) in edges], dtype=np.uint32)
    attr = np.asarray([(c, l, o, w, s) for (_a, _b, c, l, o, w, s) in edges],
                      dtype=np.float32)
    total = 0.0
    if len(ea):
        d = nxy[ea[:, 1]] - nxy[ea[:, 0]]
        total = float(np.hypot(d[:, 0], d[:, 1]).sum())
    _log(f"  road graph {len(nxy)} nodes, {len(ea)} edges, "
         f"{total / 1000:.0f} km of drivable centreline")
    return nxy, ea, attr, {"nodes": int(len(nxy)), "edges": int(len(ea)),
                           "drivable_km": round(total / 1000, 1)}


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--layers", default="data/layers")
    ap.add_argument("--dem", default="data/dem")
    ap.add_argument("--out", default="android/src/main/assets/world")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--report", default="data/reports/world_build.json")
    args = ap.parse_args()

    t0 = time.time()
    os.makedirs(args.out, exist_ok=True)
    poi = []
    if args.selftest:
        lay, dem, names, poi = synth_layers()
    else:
        _log(f"loading {args.layers}/layers.npz")
        lay = dict(np.load(os.path.join(args.layers, "layers.npz")))
        dem = dict(np.load(os.path.join(args.dem, "elevation.npz")))
        with open(os.path.join(args.layers, "road_names.json"), encoding="utf-8") as fh:
            names = json.load(fh)
        with open(os.path.join(args.layers, "poi.json"), encoding="utf-8") as fh:
            poi = json.load(fh)

    landmask, cover, dtm, canopy, rstats = build_rasters(lay, dem)
    blob, index, name_list, cmeta = build_chunks(lay, canopy, dtm, landmask,
                                                 rstats, names)
    nxy, ea, eattr, gmeta = build_road_graph(lay, names)
    tpts, toff, tcls, tmeta = build_transit(lay)

    nx, nz = rstats["nx"], rstats["nz"]
    build_id = hashlib.sha256(
        f"{aoi.AOI_VERSION}|{nx}x{nz}|{cmeta['buildings']}|{gmeta['edges']}"
        .encode()).hexdigest()[:32].encode()

    # ---- terrain -----------------------------------------------------------
    w = wf.Writer(build_id)
    w.add_grid_i16("elev_dm", dtm, nx, nz)
    w.add("cover", wf.KIND_GRID_U8, cover.tobytes(order="C"))
    bits = np.packbits(landmask.reshape(-1) > 0)
    w.add("landmask", wf.KIND_BITSET, bits.tobytes())
    w.add("rmeta", wf.KIND_JSON, json.dumps(rstats).encode())
    tpath = os.path.join(args.out, "terrain.mwl")
    tbytes = w.write(tpath)
    _log(f"wrote {tpath} {tbytes / 1e6:.1f} MB")

    # ---- chunks ------------------------------------------------------------
    w = wf.Writer(build_id)
    w.add("cindex", wf.KIND_INDEX, index.astype("<u4").tobytes())
    w.add_stored("cdata", wf.KIND_VECTOR, blob)
    w.add("names", wf.KIND_TABLE, wf.pack_strings(name_list))
    w.add("cmeta", wf.KIND_JSON, json.dumps(cmeta).encode())
    cpath = os.path.join(args.out, "chunks.mwl")
    cbytes = w.write(cpath)
    _log(f"wrote {cpath} {cbytes / 1e6:.1f} MB")

    # ---- network + places --------------------------------------------------
    w = wf.Writer(build_id)
    w.add("gnodes", wf.KIND_VECTOR, nxy.astype("<f4").tobytes())
    w.add("gedges", wf.KIND_VECTOR, ea.astype("<u4").tobytes())
    w.add("gattr", wf.KIND_VECTOR, eattr.astype("<f4").tobytes())
    places = [p for p in poi]
    w.add("places", wf.KIND_JSON, json.dumps(places).encode())
    w.add("gmeta", wf.KIND_JSON, json.dumps(gmeta).encode())
    w.add("tpts", wf.KIND_VECTOR, tpts.tobytes())
    w.add("toff", wf.KIND_VECTOR, toff.tobytes())
    w.add("tcls", wf.KIND_VECTOR, tcls.tobytes())
    w.add("tmeta", wf.KIND_JSON, json.dumps(tmeta).encode())
    npath = os.path.join(args.out, "network.mwl")
    nbytes = w.write(npath)
    _log(f"wrote {npath} {nbytes / 1e6:.1f} MB")

    # ---- manifest ----------------------------------------------------------
    prov = {}
    for key, path in (("osm", "data/provenance/osm.json"),
                      ("dem", "data/provenance/dem.json")):
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                prov[key] = json.load(fh)
    manifest = {
        "format": "MWL2",
        "aoi_name": aoi.AOI_NAME,
        "aoi_version": aoi.AOI_VERSION,
        "build_id": build_id.decode(),
        "selftest": bool(args.selftest),
        "origin_lat": aoi.ORIGIN_LAT, "origin_lon": aoi.ORIGIN_LON,
        "bounds_lonlat": list(aoi.AOI_BOUNDS),
        "bounds_xz": [round(v, 1) for v in aoi.aoi_bounds_xz()],
        "raster": rstats, "chunks": cmeta, "graph": gmeta,
        "transit": tmeta,
        "places": len(places),
        "checkpoints": [{"group": g, "key": k, "label": l, "lat": la, "lon": lo,
                         "expect": e} for (g, k, l, la, lo, e) in aoi.CHECKPOINTS],
        "sources": prov,
        "attribution": [
            "\u00a9 OpenStreetMap contributors (ODbL 1.0)",
            "Copernicus DEM \u00a9 DLR e.V. / Airbus DS, ESA",
        ],
    }
    w = wf.Writer(build_id)
    w.add("manifest", wf.KIND_JSON, json.dumps(manifest).encode())
    mpath = os.path.join(args.out, "manifest.mwl")
    mbytes = w.write(mpath)
    _log(f"wrote {mpath} {mbytes / 1e6:.3f} MB")

    total = tbytes + cbytes + nbytes + mbytes
    report = {"seconds": round(time.time() - t0, 1),
              "total_bytes": total,
              "files": {"terrain.mwl": tbytes, "chunks.mwl": cbytes,
                        "network.mwl": nbytes, "manifest.mwl": mbytes},
              "manifest": manifest}
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1)
    _log(f"packaged world {total / 1e6:.1f} MB in {time.time() - t0:.0f}s")
    _log("OK")


if __name__ == "__main__":
    main()
