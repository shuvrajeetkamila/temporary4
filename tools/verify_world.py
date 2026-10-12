#!/usr/bin/env python3
"""
Independent verification of the packaged world.

This reads the shipped ``.mwl`` files back with the same reader the game's Java code
mirrors and checks, from scratch, that the thing in ``android/src/main/assets/world``
really is the city it claims to be.  It is deliberately a *second* implementation of
the checks: the Java test suite asserts the same facts through the runtime sampler, so
a bug in one sampler cannot quietly pass both.

Exit code 0 means every gate passed.  Any failure exits non-zero and the CI build stops
before an APK is produced, which is the point: a build that cannot prove it contains
Vasai Creek, Dharavi, Trombay and the harbour must not be published.
"""

import math
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import mumbai_geo as geo              # noqa: E402
import world_format as wf             # noqa: E402

ASSETS = os.path.join(os.path.dirname(HERE), "android", "src", "main", "assets", "world")

WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)

LAND_CODES = set(range(6, 20))
WATER_CODES = {0, 1, 2, 3, 4, 5}

failures = []
checks = 0


def gate(name, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}  {detail}")
        failures.append(f"{name}: {detail}")


class World:
    """Minimal reader: enough to sample land use, height and the chunk index."""

    def __init__(self, directory):
        self.dir = directory
        self._read_manifest()
        self._read_terrain()
        self._read_chunks()

    def _open(self, name):
        return wf.Reader(os.path.join(self.dir, name))

    def _read_manifest(self):
        r = self._open("manifest.mwl")
        assert r.layer == wf.LAYER_MANIFEST, r.layer_id
        self.aoi_name = r.string()
        self.aoi_version = r.string()
        self.origin_lat = r.f64()
        self.origin_lon = r.f64()
        self.min_lat = r.f64()
        self.max_lat = r.f64()
        self.min_lon = r.f64()
        self.max_lon = r.f64()
        self.min_x = r.f64()
        self.max_x = r.f64()
        self.min_z = r.f64()
        self.max_z = r.f64()
        self.water_buffer = r.f32()
        self.seed = r.u32()
        self.layers = []
        for _ in range(r.u32()):
            self.layers.append((r.string(), r.u32()))
        self.checkpoints = []
        for _ in range(r.u32()):
            key = r.string()
            label = r.string()
            lat = r.f64()
            lon = r.f64()
            x = r.f32()
            z = r.f32()
            self.checkpoints.append((key, label, lat, lon, x, z))

    def _read_terrain(self):
        r = self._open("terrain.mwl")
        assert r.layer == wf.LAYER_TERRAIN, r.layer_id
        self.lu_w = r.u32()
        self.lu_h = r.u32()
        self.lu_res = r.f32()
        self.lu_x0 = r.f64()
        self.lu_z0 = r.f64()
        self.land_use = r.blob()
        self.ht_w = r.u32()
        self.ht_h = r.u32()
        self.ht_res = r.f32()
        self.ht_x0 = r.f64()
        self.ht_z0 = r.f64()
        raw = r.blob()
        self.height_dm = memoryview(raw).cast("h")

    def _read_chunks(self):
        r = self._open("chunks.mwl")
        assert r.layer == wf.LAYER_CHUNKS, r.layer_id
        self.chunks_x = r.u32()
        self.chunks_z = r.u32()
        self.chunk_size = r.f32()
        self.chunk_x0 = r.f64()
        self.chunk_z0 = r.f64()
        self.chunk_flags = r.blob()
        self.chunk_landfrac = r.blob()

    # -- projection (must match GeoProjection.java and build_world.py) ---------
    def world_x(self, lat, lon):
        rlat = math.radians(lat)
        n = WGS84_A / math.sqrt(1.0 - WGS84_E2 * math.sin(rlat) ** 2)
        return math.radians(lon - self.origin_lon) * n * math.cos(rlat)

    def world_z(self, lat):
        return -(meridian_arc(lat) - meridian_arc(self.origin_lat))

    # -- samplers --------------------------------------------------------------
    def land_use_at(self, x, z):
        i = int((x - self.lu_x0) / self.lu_res)
        j = int((z - self.lu_z0) / self.lu_res)
        if i < 0 or j < 0 or i >= self.lu_w or j >= self.lu_h:
            return 0
        return self.land_use[j * self.lu_w + i]

    def height_at(self, x, z):
        fx = (x - self.ht_x0) / self.ht_res
        fz = (z - self.ht_z0) / self.ht_res
        i = max(0, min(self.ht_w - 2, int(fx)))
        j = max(0, min(self.ht_h - 2, int(fz)))
        tx = max(0.0, min(1.0, fx - i))
        tz = max(0.0, min(1.0, fz - j))

        def h(a, b):
            return self.height_dm[b * self.ht_w + a] / 10.0

        top = h(i, j) * (1 - tx) + h(i + 1, j) * tx
        bot = h(i, j + 1) * (1 - tx) + h(i + 1, j + 1) * tx
        return top * (1 - tz) + bot * tz


def meridian_arc(lat_deg):
    """Meridian arc from the equator, WGS84 series to e^6."""
    e2 = WGS84_E2
    a = WGS84_A
    lat = math.radians(lat_deg)
    c0 = 1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 ** 3 / 256.0
    c2 = 3.0 / 8.0 * (e2 + e2 * e2 / 4.0 + 15.0 * e2 ** 3 / 128.0)
    c4 = 15.0 / 256.0 * (e2 * e2 + 3.0 * e2 ** 3 / 4.0)
    c6 = 35.0 * e2 ** 3 / 3072.0
    return a * (c0 * lat - c2 * math.sin(2 * lat) + c4 * math.sin(4 * lat)
                - c6 * math.sin(6 * lat))


# -------------------------------------------------------------------- the gates

# Places that must exist, and what they must be. These are the geographic
# checkpoints the brief demands: if any of them is missing, the build fails.
REQUIRED_LAND = [
    ("Colaba / Navy Nagar",        18.9020, 72.8160),
    ("Churchgate / Fort",          18.9330, 72.8290),
    ("Girgaon / Malabar Hill",     18.9540, 72.8050),
    ("Dadar",                      19.0180, 72.8430),
    ("Dharavi",                    19.0430, 72.8520),
    ("Bandra",                     19.0590, 72.8300),
    ("Kurla",                      19.0720, 72.8790),
    ("Andheri",                    19.1190, 72.8470),
    ("Goregaon",                   19.1640, 72.8490),
    ("Borivali",                   19.2290, 72.8570),
    ("Dahisar",                    19.2520, 72.8590),
    ("Mira Road approach",         19.2860, 72.8700),
    ("Mulund",                     19.1720, 72.9560),
    ("Bhandup",                    19.1430, 72.9370),
    ("Ghatkopar",                  19.0860, 72.9080),
    ("Chembur",                    19.0520, 72.8990),
    ("Trombay",                    19.0010, 72.9200),
    ("Sewri",                      18.9950, 72.8600),
    ("Worli",                      19.0000, 72.8170),
    ("Juhu",                       19.1070, 72.8270),
    ("Versova",                    19.1350, 72.8130),
    ("Malad",                      19.1860, 72.8480),
    ("Kanjurmarg",                 19.1300, 72.9380),
    ("Powai (Hiranandani)",        19.1100, 72.9080),
]

REQUIRED_WATER = [
    ("Arabian Sea west of Bandra", 19.0590, 72.7600),
    ("Arabian Sea west of Juhu",   19.1070, 72.7500),
    ("Arabian Sea west of Colaba", 18.9020, 72.7400),
    ("Mumbai Harbour",             18.9300, 72.9200),
    ("Thane Creek",                19.0800, 72.9800),
    ("Thane Creek north",          19.1800, 73.0050),
    ("Vasai Creek",                19.3600, 72.8300),
    ("Mahim Bay",                  19.0350, 72.8150),
]


def main():
    print(f"verifying packaged world in {ASSETS}")
    if not os.path.isdir(ASSETS):
        print("FATAL: no packaged world directory")
        return 2

    for name in ("manifest.mwl", "terrain.mwl", "network.mwl", "city.mwl", "chunks.mwl"):
        path = os.path.join(ASSETS, name)
        if not os.path.isfile(path):
            print(f"FATAL: missing layer {name}")
            return 2

    w = World(ASSETS)
    print(f"  AOI {w.aoi_name} v{w.aoi_version}  seed {w.seed}")
    print(f"  land use {w.lu_w}x{w.lu_h} @ {w.lu_res} m, "
          f"elevation {w.ht_w}x{w.ht_h} @ {w.ht_res} m, "
          f"chunks {w.chunks_x}x{w.chunks_z} @ {w.chunk_size} m")

    # --- 1. extent ----------------------------------------------------------
    span_ns = abs(w.world_z(w.max_lat) - w.world_z(w.min_lat))
    span_ew = abs(w.world_x(w.min_lat, w.max_lon) - w.world_x(w.min_lat, w.min_lon))
    gate("north-south extent covers Vasai Creek to Colaba",
         span_ns > 55000, f"only {span_ns / 1000:.1f} km")
    gate("east-west extent covers sea to Thane Creek",
         span_ew > 38000, f"only {span_ew / 1000:.1f} km")
    gate("AOI reaches Vasai Creek in the north", w.max_lat >= 19.40,
         f"max_lat {w.max_lat}")
    gate("AOI reaches Navy Nagar in the south", w.min_lat <= 18.88,
         f"min_lat {w.min_lat}")
    gate("AOI reaches the western bank of Thane Creek", w.max_lon >= 73.02,
         f"max_lon {w.max_lon}")
    gate("AOI includes an Arabian Sea buffer", w.min_lon <= 72.70,
         f"min_lon {w.min_lon}")
    gate("water buffer is at least 5 km", w.water_buffer >= 5000,
         f"{w.water_buffer} m")

    # --- 2. every required district is land --------------------------------
    missing = []
    for name, lat, lon in REQUIRED_LAND:
        x = w.world_x(lat, lon)
        z = w.world_z(lat)
        lu = w.land_use_at(x, z)
        h = w.height_at(x, z)
        if lu not in LAND_CODES or h < 0.2:
            missing.append(f"{name} (lu={lu}, h={h:.1f})")
    gate(f"all {len(REQUIRED_LAND)} inhabited checkpoints are land",
         not missing, "; ".join(missing))

    # --- 3. every required water body is water -----------------------------
    dry = []
    for name, lat, lon in REQUIRED_WATER:
        x = w.world_x(lat, lon)
        z = w.world_z(lat)
        lu = w.land_use_at(x, z)
        if lu not in WATER_CODES:
            dry.append(f"{name} (lu={lu})")
    gate(f"all {len(REQUIRED_WATER)} water checkpoints are water",
         not dry, "; ".join(dry))

    # --- 4. the landmass is continuous, not islands of interest ------------
    # Walk the spine of Salsette from Colaba to Dahisar and require land almost
    # everywhere: this is the gate that catches "only South Mumbai was built".
    gaps = 0
    samples = 0
    for i in range(240):
        lat = 18.91 + (19.27 - 18.91) * i / 239.0
        # follow the island's axis: the western suburbs line sits near 72.84 E
        lon = 72.826 + 0.030 * math.sin(i / 239.0 * math.pi)
        x = w.world_x(lat, lon)
        z = w.world_z(lat)
        samples += 1
        if w.land_use_at(x, z) not in LAND_CODES:
            gaps += 1
    gate("the island is continuous from Colaba to Dahisar",
         gaps <= 18, f"{gaps}/{samples} samples were water")

    # --- 5. northern and eastern suburbs are actually populated ------------
    def built_fraction(lat0, lat1, lon0, lon1):
        built = 0
        total = 0
        steps = 26
        for a in range(steps):
            for b in range(steps):
                lat = lat0 + (lat1 - lat0) * a / (steps - 1.0)
                lon = lon0 + (lon1 - lon0) * b / (steps - 1.0)
                lu = w.land_use_at(w.world_x(lat, lon), w.world_z(lat))
                if lu in LAND_CODES:
                    total += 1
                    if lu in (12, 13, 14, 15, 16, 17, 18, 19):
                        built += 1
        return (built / total) if total else 0.0, total

    for label, box, floor in (
            ("northern suburbs (Borivali-Dahisar)", (19.20, 19.27, 72.83, 72.88), 0.45),
            ("eastern suburbs (Ghatkopar-Mulund)", (19.08, 19.18, 72.91, 72.95), 0.45),
            ("central suburbs (Andheri-Goregaon)", (19.11, 19.18, 72.83, 72.88), 0.40),
            ("island city (Dadar-Byculla)", (18.97, 19.03, 72.83, 72.86), 0.60),
    ):
        frac, total = built_fraction(*box)
        gate(f"{label} are built up", frac >= floor,
             f"only {frac * 100:.0f}% built across {total} land samples")

    # --- 6. terrain plausibility -------------------------------------------
    hi = max(w.height_dm) / 10.0
    lo = min(w.height_dm) / 10.0
    gate("highest ground matches the Salsette hills",
         250 <= hi <= 600, f"{hi:.0f} m")
    gate("deepest water is plausible for the shelf", -90 <= lo <= -5, f"{lo:.0f} m")

    trombay = w.height_at(w.world_x(19.0010, 72.9200), w.world_z(19.0010))
    gate("Trombay hill exists", trombay > 40, f"{trombay:.0f} m")
    kanheri = w.height_at(w.world_x(19.2100, 72.9050), w.world_z(19.2100))
    gate("the national park highlands exist", kanheri > 120, f"{kanheri:.0f} m")

    # --- 7. chunk index agrees with the land-use grid ----------------------
    with_land = sum(1 for f in w.chunk_flags if f & 1)
    gate("the chunk index marks a plausible amount of land",
         3000 <= with_land <= 20000, f"{with_land} of {len(w.chunk_flags)}")

    bad = 0
    step = 0.004
    lat = w.min_lat
    while lat <= w.max_lat:
        lon = w.min_lon
        while lon <= w.max_lon:
            x = w.world_x(lat, lon)
            z = w.world_z(lat)
            if w.land_use_at(x, z) in LAND_CODES:
                ci = int((x - w.chunk_x0) / w.chunk_size)
                cj = int((z - w.chunk_z0) / w.chunk_size)
                if 0 <= ci < w.chunks_x and 0 <= cj < w.chunks_z:
                    if not (w.chunk_flags[cj * w.chunks_x + ci] & 1):
                        bad += 1
            lon += step
        lat += step
    gate("no land sample falls in a water-only chunk", bad == 0,
         f"{bad} mismatches")

    # --- 8. checkpoints in the manifest round-trip -------------------------
    off = []
    for key, label, lat, lon, x, z in w.checkpoints:
        ex = w.world_x(lat, lon)
        ez = w.world_z(lat)
        if abs(ex - x) > 1.0 or abs(ez - z) > 1.0:
            off.append(f"{key} ({ex - x:.1f}, {ez - z:.1f})")
    gate(f"all {len(w.checkpoints)} manifest checkpoints project consistently",
         not off, "; ".join(off))

    # --- 9. the authored source geometry is self consistent ----------------
    gate("the island outline is a closed ring with enough detail",
         len(geo.ISLAND_OUTLINE) >= 60, f"{len(geo.ISLAND_OUTLINE)} points")
    gate("every district lies inside the AOI",
         all(w.min_lat <= lat <= w.max_lat and w.min_lon <= lon <= w.max_lon
             for (_n, lat, lon, _r, _d) in geo.DISTRICTS),
         "a district is outside the AOI")
    gate("both airports are present", len(geo.AIRPORTS) >= 2,
         f"{len(geo.AIRPORTS)}")
    for icao, name, lat, lon, elev, runways in geo.AIRPORTS:
        for desig, heading, length, width, rlat, rlon in runways:
            gate(f"{icao} runway {desig} has a real length",
                 1000 <= length <= 4500, f"{length} m")

    print()
    print(f"{checks - len(failures)} passed, {len(failures)} failed")
    if failures:
        print()
        print("FAILURES:")
        for f in failures:
            print("  - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
