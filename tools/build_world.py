#!/usr/bin/env python3
"""
Deterministic Mumbai / Salsette world compiler.

Reads the authored geographic skeleton (tools/mumbai_geo.py), projects it to the 1:1 metre
local frame used by the game, rasterises terrain + land use, compiles the road / rail graph,
city features and the streamable chunk index, and writes the packaged binary world layers
into android/src/main/assets/world/.

The compiler is pure and seeded: the same inputs always produce byte identical outputs,
which is what makes the CI coverage regression meaningful.

    python3 tools/build_world.py [--out DIR] [--report]
"""

import argparse
import hashlib
import json
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mumbai_geo as geo
from world_format import (Writer, LAYER_MANIFEST, LAYER_TERRAIN, LAYER_NETWORK,
                          LAYER_CITY, LAYER_CHUNKS, FORMAT_VERSION)

# ---------------------------------------------------------------------------------------
# Projection - must match ai.shuvrajeetkamila.mumbai.geo.GeoProjection exactly.
# ---------------------------------------------------------------------------------------

WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
ORIGIN_LAT = 19.0176
ORIGIN_LON = 72.8562


def prime_vertical(lat_deg):
    s = math.sin(math.radians(lat_deg))
    return WGS84_A / math.sqrt(1.0 - WGS84_E2 * s * s)


def meridional(lat_deg):
    s = math.sin(math.radians(lat_deg))
    t = 1.0 - WGS84_E2 * s * s
    return WGS84_A * (1.0 - WGS84_E2) / (t * math.sqrt(t))


MERIDIONAL_R = meridional(ORIGIN_LAT)


def meridian_arc(lat_deg):
    """Meridian arc from the equator (WGS84, series to e^6) - matches GeoProjection.java."""
    phi = math.radians(lat_deg)
    e2 = WGS84_E2
    e4 = e2 * e2
    e6 = e4 * e2
    a0 = 1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
    a2 = 3.0 / 8.0 * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
    a4 = 15.0 / 256.0 * (e4 + 3.0 * e6 / 4.0)
    a6 = 35.0 * e6 / 3072.0
    return WGS84_A * (a0 * phi - a2 * math.sin(2 * phi) + a4 * math.sin(4 * phi)
                      - a6 * math.sin(6 * phi))


ORIGIN_ARC = meridian_arc(ORIGIN_LAT)


def to_xz(lat, lon):
    """WGS84 -> (X east metres, Z south metres)."""
    n = prime_vertical(lat)
    x = math.radians(lon - ORIGIN_LON) * n * math.cos(math.radians(lat))
    z = -(meridian_arc(lat) - ORIGIN_ARC)
    return x, z


def lat_from_z(z):
    target = ORIGIN_ARC + (-z)
    lat = ORIGIN_LAT + math.degrees((-z) / MERIDIONAL_R)
    for _ in range(4):
        err = target - meridian_arc(lat)
        if abs(err) < 1e-9:
            break
        lat += math.degrees(err / meridional(lat))
    return lat


def poly_xz(points):
    return [to_xz(lat, lon) for (lat, lon) in points]


# ---------------------------------------------------------------------------------------
# Land use codes (kept in sync with ai.shuvrajeetkamila.mumbai.world.LandUse)
# ---------------------------------------------------------------------------------------

LU_DEEP_SEA = 0
LU_SHALLOW_SEA = 1
LU_HARBOUR = 2
LU_CREEK = 3
LU_LAKE = 4
LU_RIVER = 5
LU_BEACH = 6
LU_MUDFLAT = 7
LU_MANGROVE = 8
LU_FOREST = 9
LU_PARK = 10
LU_OPEN = 11
LU_URBAN_LOW = 12
LU_SUBURBAN = 13
LU_URBAN = 14
LU_URBAN_DENSE = 15
LU_CBD = 16
LU_INDUSTRIAL = 17
LU_AIRPORT = 18
LU_RAILYARD = 19

WATER_CODES = (LU_DEEP_SEA, LU_SHALLOW_SEA, LU_HARBOUR, LU_CREEK, LU_LAKE, LU_RIVER)

LU_RES = 25.0          # metres per land-use cell
HT_RES = 50.0          # metres per elevation sample
LAKE_DEPTH = 5.0       # metres: stored lake height is the bed, surface = bed + this
RIVER_DEPTH = 2.5      # metres: same convention for the tidal rivers
CHUNK_SIZE = 256.0     # metres per streaming chunk (dense core resolution)

SEED = 20261012


# ---------------------------------------------------------------------------------------
# Raster helpers (no scipy dependency: everything is plain numpy)
# ---------------------------------------------------------------------------------------

def rasterise_polygon(mask, poly_cells, value=True):
    """Even-odd scanline fill of a polygon given in (col,row) float cell coordinates."""
    h, w = mask.shape
    ys = [p[1] for p in poly_cells]
    y0 = max(0, int(math.floor(min(ys))))
    y1 = min(h - 1, int(math.ceil(max(ys))))
    n = len(poly_cells)
    for row in range(y0, y1 + 1):
        yc = row + 0.5
        xs = []
        for i in range(n):
            ax, ay = poly_cells[i]
            bx, by = poly_cells[(i + 1) % n]
            if (ay <= yc < by) or (by <= yc < ay):
                t = (yc - ay) / (by - ay)
                xs.append(ax + t * (bx - ax))
        if not xs:
            continue
        xs.sort()
        for k in range(0, len(xs) - 1, 2):
            xa = int(math.ceil(xs[k] - 0.5))
            xb = int(math.floor(xs[k + 1] - 0.5))
            if xb < 0 or xa > w - 1:
                continue
            xa = max(0, xa)
            xb = min(w - 1, xb)
            if xb >= xa:
                mask[row, xa:xb + 1] = value


def chamfer_distance(binary, cell_size):
    """Fast approximate Euclidean distance (metres) to the nearest True cell."""
    big = 1e9
    dist = np.where(binary, 0.0, big).astype(np.float32)
    h, w = dist.shape
    d1 = 1.0
    d2 = math.sqrt(2.0)
    # forward pass
    for r in range(h):
        row = dist[r]
        if r > 0:
            prev = dist[r - 1]
            np.minimum(row, prev + d1, out=row)
            np.minimum(row[1:], prev[:-1] + d2, out=row[1:])
            np.minimum(row[:-1], prev[1:] + d2, out=row[:-1])
        for c in range(1, w):
            v = row[c - 1] + d1
            if v < row[c]:
                row[c] = v
    # backward pass
    for r in range(h - 1, -1, -1):
        row = dist[r]
        if r < h - 1:
            nxt = dist[r + 1]
            np.minimum(row, nxt + d1, out=row)
            np.minimum(row[1:], nxt[:-1] + d2, out=row[1:])
            np.minimum(row[:-1], nxt[1:] + d2, out=row[:-1])
        for c in range(w - 2, -1, -1):
            v = row[c + 1] + d1
            if v < row[c]:
                row[c] = v
    return dist * cell_size


def value_noise(shape, scale, seed, octaves=4):
    """Deterministic fractal value noise in [0,1] at the given cell scale."""
    rng = np.random.default_rng(seed)
    h, w = shape
    total = np.zeros(shape, dtype=np.float32)
    norm = 0.0
    amp = 1.0
    freq = scale
    for o in range(octaves):
        gh = max(2, int(h / freq) + 2)
        gw = max(2, int(w / freq) + 2)
        grid = rng.random((gh, gw)).astype(np.float32)
        ys = np.linspace(0, gh - 1.001, h, dtype=np.float32)
        xs = np.linspace(0, gw - 1.001, w, dtype=np.float32)
        y0 = ys.astype(np.int32)
        x0 = xs.astype(np.int32)
        fy = (ys - y0)[:, None]
        fx = (xs - x0)[None, :]
        fy = fy * fy * (3 - 2 * fy)
        fx = fx * fx * (3 - 2 * fx)
        g00 = grid[np.ix_(y0, x0)]
        g01 = grid[np.ix_(y0, x0 + 1)]
        g10 = grid[np.ix_(y0 + 1, x0)]
        g11 = grid[np.ix_(y0 + 1, x0 + 1)]
        top = g00 + (g01 - g00) * fx
        bot = g10 + (g11 - g10) * fx
        total += amp * (top + (bot - top) * fy)
        norm += amp
        amp *= 0.5
        freq = max(2.0, freq * 0.5)
    return total / norm


# ---------------------------------------------------------------------------------------
# Compiler
# ---------------------------------------------------------------------------------------

class WorldBuild:
    def __init__(self, out_dir):
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)
        self.report = {"generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                       "aoi_version": geo.AOI_VERSION,
                       "format_version": FORMAT_VERSION,
                       "seed": SEED}
        self._compute_bounds()

    # -- bounds ------------------------------------------------------------------
    def _compute_bounds(self):
        b = geo.AOI_BOUNDS
        corners = [(b["min_lat"], b["min_lon"]), (b["min_lat"], b["max_lon"]),
                   (b["max_lat"], b["min_lon"]), (b["max_lat"], b["max_lon"])]
        xs, zs = [], []
        for lat, lon in corners:
            x, z = to_xz(lat, lon)
            xs.append(x)
            zs.append(z)
        self.min_x = math.floor(min(xs) / LU_RES) * LU_RES
        self.max_x = math.ceil(max(xs) / LU_RES) * LU_RES
        self.min_z = math.floor(min(zs) / LU_RES) * LU_RES
        self.max_z = math.ceil(max(zs) / LU_RES) * LU_RES
        self.lu_w = int(round((self.max_x - self.min_x) / LU_RES))
        self.lu_h = int(round((self.max_z - self.min_z) / LU_RES))
        self.ht_w = int(math.ceil((self.max_x - self.min_x) / HT_RES)) + 1
        self.ht_h = int(math.ceil((self.max_z - self.min_z) / HT_RES)) + 1
        self.chunks_x = int(math.ceil((self.max_x - self.min_x) / CHUNK_SIZE))
        self.chunks_z = int(math.ceil((self.max_z - self.min_z) / CHUNK_SIZE))
        print(f"world bbox  X [{self.min_x:.0f} .. {self.max_x:.0f}] "
              f"Z [{self.min_z:.0f} .. {self.max_z:.0f}] metres")
        print(f"land use    {self.lu_w} x {self.lu_h} @ {LU_RES} m")
        print(f"elevation   {self.ht_w} x {self.ht_h} @ {HT_RES} m")
        print(f"chunks      {self.chunks_x} x {self.chunks_z} @ {CHUNK_SIZE} m "
              f"= {self.chunks_x * self.chunks_z}")

    def xz_to_lu_cell(self, x, z):
        return ((x - self.min_x) / LU_RES, (z - self.min_z) / LU_RES)

    def poly_to_lu_cells(self, latlon_pts):
        return [self.xz_to_lu_cell(*to_xz(lat, lon)) for (lat, lon) in latlon_pts]

    # -- land use + elevation ------------------------------------------------------
    def build_terrain(self):
        shape = (self.lu_h, self.lu_w)
        print("rasterising island outline ...")
        land = np.zeros(shape, dtype=bool)
        rasterise_polygon(land, self.poly_to_lu_cells(geo.ISLAND_OUTLINE), True)

        islands = np.zeros(shape, dtype=bool)
        for name, poly in geo.HARBOUR_ISLANDS:
            rasterise_polygon(islands, self.poly_to_lu_cells(poly), True)
        land |= islands

        lu = np.full(shape, LU_DEEP_SEA, dtype=np.uint8)

        print("computing distance fields ...")
        dist_to_land = chamfer_distance(land, LU_RES)
        dist_to_sea = chamfer_distance(~land, LU_RES)

        # --- water classes ---------------------------------------------------
        water = ~land
        lu[water & (dist_to_land < 2500.0)] = LU_SHALLOW_SEA

        # Harbour: water east of the southern island, south of Vikhroli.
        zz, xx = np.mgrid[0:self.lu_h, 0:self.lu_w].astype(np.float32)
        wx = self.min_x + (xx + 0.5) * LU_RES
        wz = self.min_z + (zz + 0.5) * LU_RES
        lat_lut = np.array([lat_from_z(float(self.min_z + (j + 0.5) * LU_RES))
                            for j in range(self.lu_h)], dtype=np.float64)
        lat_grid = np.repeat(lat_lut[:, None], self.lu_w, axis=1)
        cos_lat = np.cos(np.radians(lat_grid))
        n_lat = WGS84_A / np.sqrt(1.0 - WGS84_E2 * np.sin(np.radians(lat_grid)) ** 2)
        lon_grid = ORIGIN_LON + np.degrees(wx / np.maximum(n_lat * cos_lat, 1.0))
        harbour = water & (lon_grid > 72.838) & (lat_grid < 19.02) & (lat_grid > 18.84)
        lu[harbour] = LU_HARBOUR
        creek = water & (lon_grid > 72.90) & (lat_grid >= 19.02)
        lu[creek] = LU_CREEK
        vasai = water & (lat_grid > 19.30) & (lon_grid > 72.79)
        lu[vasai] = LU_CREEK
        # West coast creeks (Gorai / Malad / Versova / Mahim) - water close to land
        westcreek = water & (dist_to_land < 900.0) & (lon_grid < 72.86) & (lat_grid > 19.01)
        lu[westcreek & (lu == LU_SHALLOW_SEA)] = LU_CREEK

        # --- land base --------------------------------------------------------
        lu[land] = LU_OPEN

        print("painting land use ...")
        # district density
        dens = np.zeros(shape, dtype=np.float32)
        weight = np.zeros(shape, dtype=np.float32)
        for (name, lat, lon, radius, density) in geo.DISTRICTS:
            cx, cz = to_xz(lat, lon)
            d2 = (wx - cx) ** 2 + (wz - cz) ** 2
            r = max(400.0, radius)
            w = np.exp(-d2 / (2.0 * (r * 0.85) ** 2)).astype(np.float32)
            dens += w * float(density)
            weight += w
        dens = np.where(weight > 1e-4, dens / np.maximum(weight, 1e-4), 0.0)
        noise = value_noise(shape, 90.0, SEED, 4)
        dens = dens + (noise - 0.5) * 0.9

        lu[land & (dens < 0.6)] = LU_OPEN
        lu[land & (dens >= 0.6) & (dens < 1.5)] = LU_URBAN_LOW
        lu[land & (dens >= 1.5) & (dens < 2.5)] = LU_SUBURBAN
        lu[land & (dens >= 2.5) & (dens < 3.4)] = LU_URBAN
        lu[land & (dens >= 3.4) & (dens < 4.4)] = LU_URBAN_DENSE
        lu[land & (dens >= 4.4)] = LU_CBD

        # natural overrides
        park = np.zeros(shape, dtype=bool)
        rasterise_polygon(park, self.poly_to_lu_cells(geo.NATIONAL_PARK), True)
        rasterise_polygon(park, self.poly_to_lu_cells(geo.AAREY_FOREST), True)
        lu[park & land] = LU_FOREST

        for name, poly, navigable in geo.LAKES:
            lake = np.zeros(shape, dtype=bool)
            rasterise_polygon(lake, self.poly_to_lu_cells(poly), True)
            lu[lake] = LU_LAKE

        # Railway yards: flat, fenced, industrial-grade ground.
        for (name, lat, lon, heading, length, width) in geo.RAILYARDS:
            self._paint_runway(lu, wx, wz, lat, lon, heading, length, width,
                               LU_RAILYARD, only=land)

        # Parks and maidans: explicit green inside the built-up fabric.
        for (name, lat, lon, radius) in geo.PARKS:
            cx, cz = to_xz(lat, lon)
            d2 = (wx - cx) ** 2 + (wz - cz) ** 2
            lu[land & (d2 < radius * radius)] = LU_PARK

        # Rivers: narrow bands cut through whatever land use they cross. They are
        # painted after the lakes so a river leaving a lake stays continuous.
        for name, line, width in geo.RIVERS:
            self._paint_polyline(lu, wx, wz, line, max(width, LU_RES * 1.6),
                                 LU_RIVER, only=land)

        mang = np.zeros(shape, dtype=bool)
        for poly in geo.MANGROVE_BELTS:
            rasterise_polygon(mang, self.poly_to_lu_cells(poly), True)
        lu[mang & land] = LU_MANGROVE
        lu[mang & ~land & (dist_to_land < 500)] = LU_MUDFLAT

        # beaches: land within 120 m of the open sea on the west coast
        beach_band = land & (dist_to_sea < 110.0) & (lon_grid < 72.84)
        lu[beach_band] = LU_BEACH
        for (name, lat, lon, length) in geo.BEACHES:
            cx, cz = to_xz(lat, lon)
            d2 = (wx - cx) ** 2 + (wz - cz) ** 2
            sel = land & (d2 < (length * 0.5) ** 2) & (dist_to_sea < 190.0)
            lu[sel] = LU_BEACH

        # mudflats on the harbour side
        mudflat = land & (dist_to_sea < 160.0) & (lon_grid > 72.85) & (lat_grid < 19.05)
        lu[mudflat] = LU_MUDFLAT

        # airports
        for (icao, name, lat, lon, elev, runways) in geo.AIRPORTS:
            for (desig, heading, length, width, rlat, rlon) in runways:
                self._paint_runway(lu, wx, wz, rlat, rlon, heading, length, width + 260.0,
                                   LU_AIRPORT)

        # industrial / port
        for (name, lat, lon, heading, kind, a, b) in geo.LANDMARKS:
            if kind in ("industrial", "port"):
                cx, cz = to_xz(lat, lon)
                d2 = (wx - cx) ** 2 + (wz - cz) ** 2
                lu[land & (d2 < (a * 0.9) ** 2)] = LU_INDUSTRIAL

        self.land = land
        self.lu = lu
        self.dist_to_land = dist_to_land
        self.dist_to_sea = dist_to_sea
        self.wx = wx
        self.wz = wz
        self.lat_grid = lat_grid
        self.lon_grid = lon_grid

        print("building elevation ...")
        self.height = self._build_height()

    def _paint_polyline(self, grid, wx, wz, latlon_pts, width, code, only=None):
        """Stamps a constant width band along a polyline into a raster.

        Works segment by segment inside the segment's bounding box so the cost is
        proportional to the painted area rather than to the whole grid.
        """
        pts = [to_xz(la, lo) for (la, lo) in latlon_pts]
        half = width * 0.5
        for i in range(len(pts) - 1):
            ax, az = pts[i]
            bx, bz = pts[i + 1]
            i0 = int(max(0, math.floor((min(ax, bx) - half - self.min_x) / LU_RES)))
            i1 = int(min(self.lu_w, math.ceil((max(ax, bx) + half - self.min_x) / LU_RES) + 1))
            j0 = int(max(0, math.floor((min(az, bz) - half - self.min_z) / LU_RES)))
            j1 = int(min(self.lu_h, math.ceil((max(az, bz) + half - self.min_z) / LU_RES) + 1))
            if i1 <= i0 or j1 <= j0:
                continue
            sx = wx[j0:j1, i0:i1]
            sz = wz[j0:j1, i0:i1]
            ex, ez = bx - ax, bz - az
            len2 = ex * ex + ez * ez
            if len2 < 1e-6:
                continue
            t = np.clip(((sx - ax) * ex + (sz - az) * ez) / len2, 0.0, 1.0)
            dx = sx - (ax + t * ex)
            dz = sz - (az + t * ez)
            sel = (dx * dx + dz * dz) <= half * half
            if only is not None:
                sel &= only[j0:j1, i0:i1]
            sub = grid[j0:j1, i0:i1]
            sub[sel] = code
            grid[j0:j1, i0:i1] = sub

    def _paint_runway(self, lu, wx, wz, lat, lon, heading_deg, length, width, code,
                      only=None):
        cx, cz = to_xz(lat, lon)
        hd = math.radians(heading_deg)
        # Heading 0 = north (-Z), 90 = east (+X)
        ux, uz = math.sin(hd), -math.cos(hd)
        px, pz = -uz, ux
        dx = wx - cx
        dz = wz - cz
        along = dx * ux + dz * uz
        across = dx * px + dz * pz
        sel = (np.abs(along) < length * 0.5) & (np.abs(across) < width * 0.5)
        if only is not None:
            sel &= only
        lu[sel] = code

    def _build_height(self):
        hw, hh = self.ht_w, self.ht_h
        xs = self.min_x + np.arange(hw, dtype=np.float32) * HT_RES
        zs = self.min_z + np.arange(hh, dtype=np.float32) * HT_RES
        gx = xs[None, :].repeat(hh, axis=0)
        gz = zs[:, None].repeat(hw, axis=1)

        # sample land use / distances at elevation resolution
        ci = np.clip(((gx - self.min_x) / LU_RES).astype(np.int32), 0, self.lu_w - 1)
        cj = np.clip(((gz - self.min_z) / LU_RES).astype(np.int32), 0, self.lu_h - 1)
        lu = self.lu[cj, ci]
        land = self.land[cj, ci]
        d_land = self.dist_to_land[cj, ci]
        d_sea = self.dist_to_sea[cj, ci]

        h = np.zeros((hh, hw), dtype=np.float32)

        # --- bathymetry: documented deterministic coastal depth approximation -------
        # depth grows with distance offshore, capped per water class.
        depth = -(1.2 + 11.0 * (1.0 - np.exp(-d_land / 2600.0)))
        depth = np.where(lu == LU_CREEK, -(1.0 + 6.0 * (1.0 - np.exp(-d_land / 700.0))), depth)
        depth = np.where(lu == LU_HARBOUR, -(2.0 + 10.0 * (1.0 - np.exp(-d_land / 1500.0))), depth)
        depth = np.where(lu == LU_LAKE, -4.5, depth)
        h = np.where(land, 0.0, depth).astype(np.float32)

        # --- coastal plain -----------------------------------------------------
        plain = 2.0 + 5.0 * (1.0 - np.exp(-d_sea / 2200.0))
        rolling = (value_noise((hh, hw), 36.0, SEED + 11, 5) - 0.5) * 7.0
        land_h = plain + rolling
        land_h = np.maximum(land_h, 1.2)

        # --- hills -------------------------------------------------------------
        for (name, lat, lon, peak, radius) in geo.HILLS:
            cx, cz = to_xz(lat, lon)
            d = np.sqrt((gx - cx) ** 2 + (gz - cz) ** 2)
            f = np.clip(1.0 - d / radius, 0.0, 1.0)
            land_h = land_h + peak * (f * f * (3 - 2 * f))

        # --- flatten airports to their published elevation ----------------------
        for (icao, name, lat, lon, elev, runways) in geo.AIRPORTS:
            for (desig, heading, length, width, rlat, rlon) in runways:
                cx, cz = to_xz(rlat, rlon)
                hd = math.radians(heading)
                ux, uz = math.sin(hd), -math.cos(hd)
                px, pz = -uz, ux
                dx = gx - cx
                dz = gz - cz
                along = dx * ux + dz * uz
                across = dx * px + dz * pz
                sel = (np.abs(along) < length * 0.5 + 220.0) & (np.abs(across) < width * 0.5 + 240.0)
                blend = np.clip(1.0 - (np.abs(across) / (width * 0.5 + 240.0)), 0.0, 1.0)
                land_h = np.where(sel, land_h * (1.0 - blend) + float(elev) * blend, land_h)

        h = np.where(land, land_h, h)

        # --- inland water -------------------------------------------------------
        # Lakes inside the landmass (Powai, Vihar, Tulsi, the tanks) are *above* sea
        # level, some of them several hundred metres up in the park. The stored height
        # for an inland water cell is the lake BED; the runtime reconstructs the surface
        # as bed + INLAND_DEPTH, which keeps the visible water plane, the boat buoyancy
        # and the terrain mesh consistent without shipping a second raster.
        for name, poly, navigable in geo.LAKES:
            mask = np.zeros((hh, hw), dtype=bool)
            cells = [( (x - self.min_x) / HT_RES, (z - self.min_z) / HT_RES )
                     for (x, z) in [to_xz(la, lo) for (la, lo) in poly]]
            rasterise_polygon(mask, cells, True)
            if not mask.any():
                continue
            rim = land_h[mask]
            surface = float(np.percentile(rim, 15)) if rim.size else 2.0
            surface = max(surface, 1.5)
            h = np.where(mask, np.float32(surface - LAKE_DEPTH), h)
        river = (lu == LU_RIVER)
        if river.any():
            h = np.where(river, np.maximum(land_h - RIVER_DEPTH, -2.0), h)

        # beaches slope to the water
        beach = (lu == 6)
        h = np.where(beach, np.clip(0.4 + d_sea * 0.02, 0.4, 3.0), h)
        mang = (lu == 8) | (lu == 7)
        h = np.where(mang, np.clip(0.2 + d_sea * 0.004, 0.1, 2.0), h)
        return h.astype(np.float32)

    # -- network ------------------------------------------------------------------
    def build_network(self):
        print("compiling road / rail network ...")
        self.roads = []
        for (name, cls, lanes, layer, pts) in geo.ROADS:
            xz = poly_xz(pts)
            self.roads.append({"name": name, "cls": cls, "lanes": lanes, "layer": layer,
                               "pts": xz, "length": polyline_length(xz)})
        self.rails = []
        for (name, kind, layer, pts) in geo.RAILWAYS:
            xz = poly_xz(pts)
            self.rails.append({"name": name, "kind": kind, "layer": layer,
                               "pts": xz, "length": polyline_length(xz)})
        total_road = sum(r["length"] for r in self.roads)
        total_rail = sum(r["length"] for r in self.rails)
        print(f"  roads {len(self.roads)} ({total_road / 1000.0:.1f} km), "
              f"rails {len(self.rails)} ({total_rail / 1000.0:.1f} km)")
        self.report["road_km"] = round(total_road / 1000.0, 2)
        self.report["rail_km"] = round(total_rail / 1000.0, 2)

    # -- chunk index ---------------------------------------------------------------
    def build_chunks(self):
        print("indexing streaming chunks ...")
        cx_n, cz_n = self.chunks_x, self.chunks_z
        flags = np.zeros((cz_n, cx_n), dtype=np.uint8)
        land_frac = np.zeros((cz_n, cx_n), dtype=np.uint8)
        density = np.zeros((cz_n, cx_n), dtype=np.uint8)
        road_cnt = np.zeros((cz_n, cx_n), dtype=np.uint8)
        build_cnt = np.zeros((cz_n, cx_n), dtype=np.uint16)

        # 256 m chunks over 25 m land-use cells is 10.24 cells per edge, so the cell
        # range has to be derived from world coordinates. Using a truncated integer step
        # drifts by 6 m per chunk, which is a full kilometre across the island and makes
        # the streaming flags disagree with the land-use raster.
        for cz in range(cz_n):
            z0 = self.min_z + cz * CHUNK_SIZE
            r0 = int(math.floor((z0 - self.min_z) / LU_RES))
            r1 = min(self.lu_h, int(math.ceil((z0 + CHUNK_SIZE - self.min_z) / LU_RES)))
            if r0 >= self.lu_h:
                continue
            for cx in range(cx_n):
                x0 = self.min_x + cx * CHUNK_SIZE
                c0 = int(math.floor((x0 - self.min_x) / LU_RES))
                c1 = min(self.lu_w, int(math.ceil((x0 + CHUNK_SIZE - self.min_x) / LU_RES)))
                if c0 >= self.lu_w:
                    continue
                block = self.lu[r0:r1, c0:c1]
                if block.size == 0:
                    continue
                island = ~np.isin(block, WATER_CODES)
                frac = float(island.mean())
                land_frac[cz, cx] = int(round(frac * 255))
                # dominant urban class
                urban = block[(block >= LU_URBAN_LOW)]
                density[cz, cx] = int(np.bincount(urban - LU_URBAN_LOW, minlength=6).argmax()) \
                    if urban.size else 0
                f = 0
                if frac > 0.02:
                    f |= 1          # has land
                if frac < 0.98:
                    f |= 2          # has water
                if np.isin(block, (LU_FOREST, LU_PARK, LU_MANGROVE)).any():
                    f |= 4
                if (block == LU_AIRPORT).any():
                    f |= 8
                if np.isin(block, (LU_CBD,)).any():
                    f |= 16
                flags[cz, cx] = f
                if frac > 0.05:
                    # deterministic building estimate for the coverage test
                    d = density[cz, cx]
                    build_cnt[cz, cx] = int(frac * (4 + d * 11))

        # road counts per chunk
        for r in self.roads:
            for (x, z) in densify(r["pts"], 60.0):
                cx = int((x - self.min_x) / CHUNK_SIZE)
                cz = int((z - self.min_z) / CHUNK_SIZE)
                if 0 <= cx < cx_n and 0 <= cz < cz_n and road_cnt[cz, cx] < 255:
                    road_cnt[cz, cx] += 1
                    flags[cz, cx] |= 32
        for r in self.rails:
            for (x, z) in densify(r["pts"], 60.0):
                cx = int((x - self.min_x) / CHUNK_SIZE)
                cz = int((z - self.min_z) / CHUNK_SIZE)
                if 0 <= cx < cx_n and 0 <= cz < cz_n:
                    flags[cz, cx] |= 64

        self.chunk_flags = flags
        self.chunk_land = land_frac
        self.chunk_density = density
        self.chunk_roads = road_cnt
        self.chunk_buildings = build_cnt
        populated = int((flags != 0).sum())
        land_chunks = int(((flags & 1) != 0).sum())
        print(f"  chunks total {cx_n * cz_n}, populated {populated}, with land {land_chunks}")
        self.report["chunks_total"] = cx_n * cz_n
        self.report["chunks_populated"] = populated
        self.report["chunks_with_land"] = land_chunks
        self.report["estimated_buildings"] = int(build_cnt.sum())

    # -- writers --------------------------------------------------------------------
    def write_all(self):
        crcs = {}
        crcs["terrain"] = self._write_terrain()
        crcs["network"] = self._write_network()
        crcs["city"] = self._write_city()
        crcs["chunks"] = self._write_chunks()
        self._write_manifest(crcs)
        self._write_geojson()
        self._write_report()

    def _write_terrain(self):
        w = Writer(LAYER_TERRAIN)
        w.u32(self.lu_w)
        w.u32(self.lu_h)
        w.f32(LU_RES)
        w.f64(self.min_x)
        w.f64(self.min_z)
        w.blob(self.lu.tobytes(order="C"))
        w.u32(self.ht_w)
        w.u32(self.ht_h)
        w.f32(HT_RES)
        w.f64(self.min_x)
        w.f64(self.min_z)
        hdm = np.clip(np.round(self.height * 10.0), -32000, 32000).astype("<i2")
        w.blob(hdm.tobytes(order="C"))
        path = os.path.join(self.out_dir, "terrain.mwl")
        size = w.write(path)
        print(f"  terrain.mwl {size / 1024.0:.0f} KB")
        self.report["terrain_bytes"] = size
        return w.crc()

    def _write_network(self):
        w = Writer(LAYER_NETWORK)
        w.u32(len(self.roads))
        for r in self.roads:
            w.string(r["name"])
            w.u8(r["cls"])
            w.u8(r["lanes"])
            w.i8(r["layer"])
            w.u16(len(r["pts"]))
            for (x, z) in r["pts"]:
                w.f32(x)
                w.f32(z)
        w.u32(len(self.rails))
        for r in self.rails:
            w.string(r["name"])
            w.u8(r["kind"])
            w.i8(r["layer"])
            w.u16(len(r["pts"]))
            for (x, z) in r["pts"]:
                w.f32(x)
                w.f32(z)
        w.u32(len(geo.STATIONS))
        for (name, lat, lon, line) in geo.STATIONS:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.f32(x)
            w.f32(z)
            w.u8(line)
        path = os.path.join(self.out_dir, "network.mwl")
        size = w.write(path)
        print(f"  network.mwl {size / 1024.0:.1f} KB")
        self.report["network_bytes"] = size
        return w.crc()

    def _write_city(self):
        w = Writer(LAYER_CITY)
        # districts
        w.u32(len(geo.DISTRICTS))
        for (name, lat, lon, radius, density) in geo.DISTRICTS:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.f32(x)
            w.f32(z)
            w.f32(radius)
            w.u8(density)
        # landmarks
        w.u32(len(geo.LANDMARKS))
        for (name, lat, lon, heading, kind, a, b) in geo.LANDMARKS:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.string(kind)
            w.f32(x)
            w.f32(z)
            w.f32(heading)
            w.f32(a)
            w.f32(b)
        # airports + runways
        w.u32(len(geo.AIRPORTS))
        for (icao, name, lat, lon, elev, runways) in geo.AIRPORTS:
            x, z = to_xz(lat, lon)
            w.string(icao)
            w.string(name)
            w.f32(x)
            w.f32(z)
            w.f32(elev)
            w.u16(len(runways))
            for (desig, heading, length, width, rlat, rlon) in runways:
                rx, rz = to_xz(rlat, rlon)
                w.string(desig)
                w.f32(rx)
                w.f32(rz)
                w.f32(heading)
                w.f32(length)
                w.f32(width)
        # helipads
        w.u32(len(geo.HELIPADS))
        for (name, lat, lon, elev) in geo.HELIPADS:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.f32(x)
            w.f32(z)
            w.f32(elev)
        # boat launches
        w.u32(len(geo.BOAT_LAUNCHES))
        for (name, lat, lon, water) in geo.BOAT_LAUNCHES:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.string(water)
            w.f32(x)
            w.f32(z)
        # beaches
        w.u32(len(geo.BEACHES))
        for (name, lat, lon, length) in geo.BEACHES:
            x, z = to_xz(lat, lon)
            w.string(name)
            w.f32(x)
            w.f32(z)
            w.f32(length)
        path = os.path.join(self.out_dir, "city.mwl")
        size = w.write(path)
        print(f"  city.mwl {size / 1024.0:.1f} KB")
        self.report["city_bytes"] = size
        return w.crc()

    def _write_chunks(self):
        w = Writer(LAYER_CHUNKS)
        w.u32(self.chunks_x)
        w.u32(self.chunks_z)
        w.f32(CHUNK_SIZE)
        w.f64(self.min_x)
        w.f64(self.min_z)
        w.blob(self.chunk_flags.tobytes(order="C"))
        w.blob(self.chunk_land.tobytes(order="C"))
        w.blob(self.chunk_density.tobytes(order="C"))
        w.blob(self.chunk_roads.tobytes(order="C"))
        w.blob(self.chunk_buildings.astype("<u2").tobytes(order="C"))
        path = os.path.join(self.out_dir, "chunks.mwl")
        size = w.write(path)
        print(f"  chunks.mwl {size / 1024.0:.1f} KB")
        self.report["chunks_bytes"] = size
        return w.crc()

    def _write_manifest(self, crcs):
        w = Writer(LAYER_MANIFEST)
        w.string(geo.AOI_NAME)
        w.string(geo.AOI_VERSION)
        w.f64(ORIGIN_LAT)
        w.f64(ORIGIN_LON)
        b = geo.AOI_BOUNDS
        w.f64(b["min_lat"])
        w.f64(b["max_lat"])
        w.f64(b["min_lon"])
        w.f64(b["max_lon"])
        w.f64(self.min_x)
        w.f64(self.max_x)
        w.f64(self.min_z)
        w.f64(self.max_z)
        w.f32(geo.WATER_BUFFER_M)
        w.u32(SEED)
        # layer table
        names = ["terrain", "network", "city", "chunks"]
        w.u32(len(names))
        for n in names:
            w.string(n + ".mwl")
            w.u32(crcs[n])
        # checkpoints
        w.u32(len(geo.CHECKPOINTS))
        for (key, label, lat, lon) in geo.CHECKPOINTS:
            x, z = to_xz(lat, lon)
            w.string(key)
            w.string(label)
            w.f64(lat)
            w.f64(lon)
            w.f32(x)
            w.f32(z)
        # spawn
        sname, slat, slon, shead = geo.DEFAULT_SPAWN
        sx, sz = to_xz(slat, slon)
        w.string(sname)
        w.f32(sx)
        w.f32(sz)
        w.f32(shead)
        path = os.path.join(self.out_dir, "manifest.mwl")
        size = w.write(path)
        print(f"  manifest.mwl {size} B")
        self.report["manifest_bytes"] = size
        self.report["layer_crc"] = {k: f"{v:08x}" for k, v in crcs.items()}

    def _write_geojson(self):
        """Human readable AOI + boundary report (data/geodata/MUMBAI_AOI.geojson)."""
        out = os.path.join("data", "geodata")
        os.makedirs(out, exist_ok=True)
        features = [{
            "type": "Feature",
            "properties": {"name": geo.AOI_NAME, "version": geo.AOI_VERSION,
                           "role": "physical island outline"},
            "geometry": {"type": "Polygon",
                         "coordinates": [[[lon, lat] for (lat, lon) in geo.ISLAND_OUTLINE]
                                         + [[geo.ISLAND_OUTLINE[0][1], geo.ISLAND_OUTLINE[0][0]]]]},
        }, {
            "type": "Feature",
            "properties": {"name": "AOI analysis bounds incl. marine/airspace buffer",
                           "buffer_m": geo.WATER_BUFFER_M},
            "geometry": {"type": "Polygon", "coordinates": [[
                [geo.AOI_BOUNDS["min_lon"], geo.AOI_BOUNDS["min_lat"]],
                [geo.AOI_BOUNDS["max_lon"], geo.AOI_BOUNDS["min_lat"]],
                [geo.AOI_BOUNDS["max_lon"], geo.AOI_BOUNDS["max_lat"]],
                [geo.AOI_BOUNDS["min_lon"], geo.AOI_BOUNDS["max_lat"]],
                [geo.AOI_BOUNDS["min_lon"], geo.AOI_BOUNDS["min_lat"]]]]},
        }]
        for (key, label, lat, lon) in geo.CHECKPOINTS:
            features.append({
                "type": "Feature",
                "properties": {"checkpoint": key, "label": label},
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
            })
        path = os.path.join(out, "MUMBAI_AOI.geojson")
        with open(path, "w") as f:
            json.dump({"type": "FeatureCollection", "features": features}, f, indent=1)
        print(f"  {path}")

    def _write_report(self):
        self.report.update({
            "bounds_world": {"min_x": self.min_x, "max_x": self.max_x,
                             "min_z": self.min_z, "max_z": self.max_z},
            "span_km": {"east_west": round((self.max_x - self.min_x) / 1000.0, 2),
                        "north_south": round((self.max_z - self.min_z) / 1000.0, 2)},
            "landuse_grid": [self.lu_w, self.lu_h, LU_RES],
            "height_grid": [self.ht_w, self.ht_h, HT_RES],
            "chunk_grid": [self.chunks_x, self.chunks_z, CHUNK_SIZE],
            "districts": len(geo.DISTRICTS),
            "landmarks": len(geo.LANDMARKS),
            "roads": len(geo.ROADS),
            "railways": len(geo.RAILWAYS),
            "checkpoints": len(geo.CHECKPOINTS),
        })
        total = 0
        for name in ("manifest.mwl", "terrain.mwl", "network.mwl", "city.mwl", "chunks.mwl"):
            p = os.path.join(self.out_dir, name)
            total += os.path.getsize(p)
            with open(p, "rb") as f:
                self.report.setdefault("sha256", {})[name] = hashlib.sha256(f.read()).hexdigest()
        self.report["packaged_bytes"] = total
        os.makedirs("data/geodata", exist_ok=True)
        with open("data/geodata/world_build_report.json", "w") as f:
            json.dump(self.report, f, indent=1)
        print(f"packaged world total: {total / 1024.0 / 1024.0:.2f} MB")


def polyline_length(pts):
    total = 0.0
    for i in range(1, len(pts)):
        dx = pts[i][0] - pts[i - 1][0]
        dz = pts[i][1] - pts[i - 1][1]
        total += math.hypot(dx, dz)
    return total


def densify(pts, step):
    out = []
    for i in range(1, len(pts)):
        ax, az = pts[i - 1]
        bx, bz = pts[i]
        d = math.hypot(bx - ax, bz - az)
        n = max(1, int(d / step))
        for k in range(n):
            t = k / n
            out.append((ax + (bx - ax) * t, az + (bz - az) * t))
    if pts:
        out.append(pts[-1])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="android/src/main/assets/world")
    args = ap.parse_args()
    t0 = time.time()
    build = WorldBuild(args.out)
    build.build_terrain()
    build.build_network()
    build.build_chunks()
    build.write_all()
    print(f"done in {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
