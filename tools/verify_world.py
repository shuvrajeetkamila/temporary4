#!/usr/bin/env python3
"""
Independent verification of the packaged world.

This is the gate that decides whether a build may ship. It deliberately does
not import the compiler's own data structures: it reopens the four packaged
files, re-derives the projection from first principles, and asks geographic
questions about the result. If the compiler has a bug that makes it write a
world it believes in and nobody else does, this is what catches it.

The checks that matter most are the coverage ones. It is easy to produce a
beautiful South Mumbai and quietly ship nothing north of Bandra, and that is
exactly what the brief forbids, so every checkpoint group - Vasai Creek, the
northern belt, the national park, the eastern suburbs, the harbour - has to
independently prove it contains real geometry.

  python3 tools/verify_world.py --world android/src/main/assets/world
"""

import argparse
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aoi                                                        # noqa: E402
import world_format as wf                                         # noqa: E402

PASS = "PASS"
FAIL = "FAIL"


class Gates:
    def __init__(self):
        self.rows = []
        self.failed = 0

    def check(self, group, name, ok, detail=""):
        self.rows.append((group, name, PASS if ok else FAIL, detail))
        if not ok:
            self.failed += 1
        return ok

    def report(self):
        width = max(len(r[1]) for r in self.rows) + 2
        last = None
        for g, n, st, d in self.rows:
            if g != last:
                print(f"\n[{g}]")
                last = g
            mark = "  ok  " if st == PASS else "  FAIL"
            print(f"{mark} {n:<{width}} {d}")
        print(f"\n{len(self.rows) - self.failed}/{len(self.rows)} gates passed")
        return self.failed == 0


def meridian_arc(lat_deg):
    """Re-derived here on purpose, so a bug in aoi.py cannot hide itself."""
    a, f = 6378137.0, 1 / 298.257223563
    e2 = f * (2 - f)
    lat = math.radians(lat_deg)
    return a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * lat
                - (3 / 8 * (e2 + e2 ** 2 / 4 + 15 * e2 ** 3 / 128)) * math.sin(2 * lat)
                + (15 / 256 * (e2 ** 2 + 3 * e2 ** 3 / 4)) * math.sin(4 * lat)
                - (35 * e2 ** 3 / 3072) * math.sin(6 * lat))


def project(lat, lon, lat0, lon0):
    a, f = 6378137.0, 1 / 298.257223563
    e2 = f * (2 - f)
    n = a / math.sqrt(1 - e2 * math.sin(math.radians(lat)) ** 2)
    x = math.radians(lon - lon0) * n * math.cos(math.radians(lat))
    z = -(meridian_arc(lat) - meridian_arc(lat0))
    return x, z


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--world", default="android/src/main/assets/world")
    ap.add_argument("--report", default="data/reports/verify.json")
    ap.add_argument("--allow-selftest", action="store_true")
    args = ap.parse_args()

    g = Gates()
    w = args.world

    # ---------------------------------------------------------------- files
    for f in ("manifest.mwl", "terrain.mwl", "chunks.mwl", "network.mwl"):
        p = os.path.join(w, f)
        g.check("files", f"{f} exists", os.path.exists(p),
                f"{os.path.getsize(p) / 1e6:.2f} MB" if os.path.exists(p) else "missing")
    if g.failed:
        g.report()
        return 1

    man = wf.Reader(os.path.join(w, "manifest.mwl"))
    terr = wf.Reader(os.path.join(w, "terrain.mwl"))
    chunks = wf.Reader(os.path.join(w, "chunks.mwl"))
    net = wf.Reader(os.path.join(w, "network.mwl"))
    M = json.loads(man.raw("manifest").decode())

    build_ids = {man.build_id, terr.build_id, chunks.build_id, net.build_id}
    g.check("files", "all four files share one build id", len(build_ids) == 1,
            next(iter(build_ids)))
    g.check("files", "world is not a self-test fixture",
            args.allow_selftest or not M.get("selftest", False),
            "selftest=" + str(M.get("selftest")))

    # ----------------------------------------------------------- provenance
    src = M.get("sources", {})
    osm = src.get("osm", {})
    dem = src.get("dem", {})
    g.check("provenance", "OSM source recorded", bool(osm.get("source_url")),
            str(osm.get("source_url"))[:72])
    g.check("provenance", "OSM retrieval date recorded", bool(osm.get("retrieved_utc")),
            str(osm.get("retrieved_utc")))
    g.check("provenance", "OSM extract sha256 recorded",
            len(str(osm.get("clip", {}).get("output_sha256", ""))) == 64,
            str(osm.get("clip", {}).get("output_sha256", ""))[:16] + "...")
    g.check("provenance", "OSM attribution present",
            "OpenStreetMap" in " ".join(M.get("attribution", [])),
            "; ".join(M.get("attribution", []))[:70])
    g.check("provenance", "ODbL named", "ODbL" in json.dumps(M.get("attribution", []))
            or "ODbL" in str(osm.get("licence", "")), str(osm.get("licence", "")))
    g.check("provenance", "elevation source recorded", bool(dem.get("dataset")),
            str(dem.get("dataset")))

    # -------------------------------------------------------------- rasters
    rm = M["raster"]
    nx, nz, grid = rm["nx"], rm["nz"], rm["grid_m"]
    minx, minz = rm["min_x"], rm["min_z"]
    elev = terr.grid_i16("elev_dm", nx, nz).astype(np.float32) / 10.0
    cover = np.frombuffer(terr.raw("cover"), dtype=np.uint8).reshape(nz, nx)
    mask = np.unpackbits(np.frombuffer(terr.raw("landmask"), dtype=np.uint8))[:nx * nz]
    mask = mask.reshape(nz, nx)

    def cell(lat, lon):
        x, z = project(lat, lon, M["origin_lat"], M["origin_lon"])
        i = int(round((x - minx) / grid))
        j = int(round((z - minz) / grid))
        return max(0, min(nx - 1, i)), max(0, min(nz - 1, j)), x, z

    span_x = (nx - 1) * grid / 1000.0
    span_z = (nz - 1) * grid / 1000.0
    g.check("extent", "world spans >= 40 km east-west", span_x >= 40,
            f"{span_x:.1f} km")
    g.check("extent", "world spans >= 60 km north-south", span_z >= 60,
            f"{span_z:.1f} km")
    land_frac = float(mask.mean())
    g.check("extent", "land is 20-60% of the AOI", 0.20 <= land_frac <= 0.60,
            f"{land_frac * 100:.1f}% = {land_frac * nx * nz * grid ** 2 / 1e6:.0f} km2")
    g.check("extent", "ground rises above 400 m somewhere", elev.max() >= 400,
            f"max {elev.max():.0f} m")
    g.check("extent", "no ground above 900 m", elev.max() < 900, f"max {elev.max():.0f} m")
    g.check("extent", "sea is flat at datum",
            abs(float(elev[mask == 0].mean())) < 0.05,
            f"mean {float(elev[mask == 0].mean()):.3f} m")
    g.check("extent", "land is at or above the waterline",
            float(elev[mask > 0].min()) >= 0.0,
            f"min land {float(elev[mask > 0].min()):.2f} m")

    # ------------------------------------------------- projection agreement
    worst = 0.0
    for (_grp, _k, _l, lat, lon, _e) in aoi.CHECKPOINTS:
        ax, az = aoi.to_xz(lat, lon)
        bx, bz = project(lat, lon, M["origin_lat"], M["origin_lon"])
        worst = max(worst, math.hypot(ax - bx, az - bz))
    g.check("projection", "compiler and verifier agree within 1 mm", worst < 0.001,
            f"worst {worst * 1000:.4f} mm")

    # Vincenty ground truth over a long north-south baseline.
    def vincenty(lat1, lon1, lat2, lon2):
        a, f = 6378137.0, 1 / 298.257223563
        b = a * (1 - f)
        u1 = math.atan((1 - f) * math.tan(math.radians(lat1)))
        u2 = math.atan((1 - f) * math.tan(math.radians(lat2)))
        su1, cu1, su2, cu2 = (math.sin(u1), math.cos(u1), math.sin(u2), math.cos(u2))
        lam = math.radians(lon2 - lon1)
        for _ in range(200):
            sl, cl = math.sin(lam), math.cos(lam)
            ss = math.sqrt((cu2 * sl) ** 2 + (cu1 * su2 - su1 * cu2 * cl) ** 2)
            if ss == 0:
                return 0.0
            cs = su1 * su2 + cu1 * cu2 * cl
            sig = math.atan2(ss, cs)
            sa = cu1 * cu2 * sl / ss
            c2a = 1 - sa * sa
            c2m = cs - 2 * su1 * su2 / c2a if c2a else 0
            cc = f / 16 * c2a * (4 + f * (4 - 3 * c2a))
            prev = lam
            lam = (math.radians(lon2 - lon1) + (1 - cc) * f * sa
                   * (sig + cc * ss * (c2m + cc * cs * (-1 + 2 * c2m ** 2))))
            if abs(lam - prev) < 1e-12:
                break
        u2s = c2a * (a * a - b * b) / (b * b)
        aa = 1 + u2s / 16384 * (4096 + u2s * (-768 + u2s * (320 - 175 * u2s)))
        bb = u2s / 1024 * (256 + u2s * (-128 + u2s * (74 - 47 * u2s)))
        ds = bb * ss * (c2m + bb / 4 * (cs * (-1 + 2 * c2m ** 2) - bb / 6 * c2m
                                        * (-3 + 4 * ss ** 2) * (-3 + 4 * c2m ** 2)))
        return b * aa * (sig - ds)

    pairs = [("Navy Nagar", 18.8980, 72.8120, "Dahisar", 19.2500, 72.8600),
             ("Versova", 19.1350, 72.8130, "Mulund", 19.1720, 72.9560)]
    for n1, la1, lo1, n2, la2, lo2 in pairs:
        x1, z1 = project(la1, lo1, M["origin_lat"], M["origin_lon"])
        x2, z2 = project(la2, lo2, M["origin_lat"], M["origin_lon"])
        planar = math.hypot(x2 - x1, z2 - z1)
        truth = vincenty(la1, lo1, la2, lo2)
        err = abs(planar - truth) / truth
        g.check("projection", f"{n1} to {n2} within 0.1% of Vincenty", err < 0.001,
                f"{planar:.0f} m vs {truth:.0f} m, {err * 100:.4f}%")

    # ------------------------------------------------------------ the chunks
    cm = M["chunks"]
    cnx, cnz = cm["cnx"], cm["cnz"]
    cminx, cminz, chunk_m = cm["cminx"], cm["cminz"], cm["chunk_m"]
    index = np.frombuffer(chunks.raw("cindex"), dtype="<u4").reshape(-1, 6)
    g.check("chunks", "index matches the declared grid", len(index) == cnx * cnz,
            f"{len(index)} entries, {cnx}x{cnz}")
    nonempty = int((index[:, 1] > 0).sum())
    g.check("chunks", "at least 800 chunks carry geometry", nonempty >= 800,
            f"{nonempty} non-empty")
    total_b = int(index[:, 3].sum())
    total_r = int(index[:, 4].sum())
    g.check("chunks", "at least 100k real buildings", total_b >= 100_000,
            f"{total_b:,}")
    g.check("chunks", "at least 50k road runs", total_r >= 50_000, f"{total_r:,}")

    def chunk_at(x, z):
        i = int(math.floor(x / chunk_m)) - cminx
        j = int(math.floor(z / chunk_m)) - cminz
        if 0 <= i < cnx and 0 <= j < cnz:
            return j * cnx + i
        return -1

    # -------------------------------------------------- geographic coverage
    groups = {}
    for (grp, key, label, lat, lon, expect) in aoi.CHECKPOINTS:
        i, j, x, z = cell(lat, lon)
        is_land = bool(mask[j, i])
        ok = (is_land if expect == "land" else not is_land)
        g.check(f"cp:{grp}", f"{label} is {expect}", ok,
                f"{lat},{lon} -> {'land' if is_land else 'water'}"
                f" elev {elev[j, i]:.1f} m")
        groups.setdefault(grp, []).append((label, x, z, expect))

    # Every land checkpoint must have real geometry near it. A world that
    # passes the land mask but has no buildings or roads in Mulund has not
    # actually shipped Mulund.
    for grp, items in sorted(groups.items()):
        land_items = [it for it in items if it[3] == "land"]
        if not land_items:
            continue
        b = r = 0
        for (_label, x, z, _e) in land_items:
            for dx in (-chunk_m, 0, chunk_m):
                for dz in (-chunk_m, 0, chunk_m):
                    cid = chunk_at(x + dx, z + dz)
                    if cid >= 0:
                        b += int(index[cid, 3])
                        r += int(index[cid, 4])
        g.check("coverage", f"{grp}: real geometry near its checkpoints",
                b + r >= 120,
                f"{b} buildings + {r} road runs in the 3x3 chunks around "
                f"{len(land_items)} checkpoints")
        g.check("coverage", f"{grp}: roads near its checkpoints", r >= 15,
                f"{r} road runs")
        if grp in aoi.URBAN_GROUPS:
            g.check("coverage", f"{grp}: buildings near its checkpoints", b >= 500,
                    f"{b} buildings")

    # North-south and east-west reach of actual geometry, not just the raster.
    occupied = np.argwhere(index[:, 1].reshape(cnz, cnx) > 0)
    if len(occupied):
        jz = occupied[:, 0]
        ix = occupied[:, 1]
        north = (jz.min() + cminz) * chunk_m
        south = (jz.max() + cminz + 1) * chunk_m
        west = (ix.min() + cminx) * chunk_m
        east = (ix.max() + cminx + 1) * chunk_m
        g.check("coverage", "geometry spans >= 55 km north to south",
                (south - north) / 1000 >= 55, f"{(south - north) / 1000:.1f} km")
        g.check("coverage", "geometry spans >= 30 km west to east",
                (east - west) / 1000 >= 30, f"{(east - west) / 1000:.1f} km")
        nlat = aoi.to_latlon(0, north)[0]
        slat = aoi.to_latlon(0, south)[0]
        g.check("coverage", "reaches Vasai Creek in the north", nlat >= 19.36,
                f"northernmost geometry at {nlat:.3f} N")
        g.check("coverage", "reaches Navy Nagar in the south", slat <= 18.91,
                f"southernmost geometry at {slat:.3f} N")

    # ------------------------------------------------------- the road graph
    gm = M["graph"]
    gn = np.frombuffer(net.raw("gnodes"), dtype="<f4").reshape(-1, 2)
    ge = np.frombuffer(net.raw("gedges"), dtype="<u4").reshape(-1, 2)
    g.check("network", "graph has >= 100k nodes", len(gn) >= 100_000, f"{len(gn):,}")
    g.check("network", "graph has >= 100k edges", len(ge) >= 100_000, f"{len(ge):,}")
    g.check("network", "edge endpoints are in range",
            len(ge) == 0 or int(ge.max()) < len(gn),
            f"max index {int(ge.max()) if len(ge) else 0}")
    g.check("network", ">= 3000 km of drivable road", gm["drivable_km"] >= 3000,
            f"{gm['drivable_km']:,.0f} km")
    if len(gn):
        on_land = 0
        step = max(1, len(gn) // 4000)
        sampled = gn[::step]
        for (x, z) in sampled:
            i = max(0, min(nx - 1, int(round((x - minx) / grid))))
            j = max(0, min(nz - 1, int(round((z - minz) / grid))))
            on_land += 1 if mask[j, i] else 0
        frac = on_land / len(sampled)
        g.check("network", "95% of road nodes are on land", frac >= 0.95,
                f"{frac * 100:.1f}% of {len(sampled)} sampled")

    # --------------------------------------------------------------- places
    places = json.loads(net.raw("places").decode())
    g.check("places", "at least 400 named places", len(places) >= 400, f"{len(places)}")
    names = {p["name"] for p in places}
    for must in ("Bandra", "Andheri", "Borivali", "Colaba"):
        g.check("places", f"{must} is named in the data",
                any(must.lower() in n.lower() for n in names), "")

    # --------------------------------------------------------- the manifest
    hs = cm.get("height_src", {})
    tagged = hs.get("osm_height", 0) + hs.get("osm_levels", 0)
    total = max(1, sum(hs.values()))
    g.check("heights", "building height provenance is recorded", len(hs) >= 2,
            json.dumps(hs))
    g.check("heights", "at least 10% of heights come from OSM tags",
            tagged / total >= 0.10,
            f"{tagged:,} of {total:,} = {100 * tagged / total:.1f}%")

    ok = g.report()
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as fh:
        json.dump({"passed": len(g.rows) - g.failed, "total": len(g.rows),
                   "failed": g.failed,
                   "gates": [{"group": a, "name": b, "status": c, "detail": d}
                             for (a, b, c, d) in g.rows]}, fh, indent=1)
    print(f"report written to {args.report}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
