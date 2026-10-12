#!/usr/bin/env python3
"""
Stage 3: read the clipped OSM extract and pull out the layers the game needs.

Everything here is real OpenStreetMap geometry projected into the local ENU
metre frame. Nothing is invented. Where OSM has no value for an attribute the
code says so explicitly (``height_src = "estimated"``) rather than quietly
fabricating one that looks authoritative.

The hard part is the coastline. OSM does not ship land polygons; it ships
``natural=coastline`` ways, oriented so that land is on the left. To get land
and sea this noodles them together, closes them against the AOI boundary,
polygonizes the result, and classifies each resulting face by offsetting half a
metre to the left of a coastline segment on its own boundary and asking which
face that point falls in. That is the authoritative OSM rule, and it is what
makes Salsette an island in this game rather than a guess.

  python3 tools/extract_osm.py --pbf data/osm/mumbai-aoi.osm.pbf --out data/layers
"""

import argparse
import collections
import json
import os
import re
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aoi  # noqa: E402

# --------------------------------------------------------------------------
# Road classification. Order matters: index is the game's road class id.
# width_m is the full carriageway width used when OSM gives no lane count.
# --------------------------------------------------------------------------
ROAD_CLASSES = [
    # key,            id, default_lanes, width_m, speed_kph, kind
    ("motorway",       0, 6, 21.0, 100, "road"),
    ("trunk",          1, 6, 20.0,  80, "road"),
    ("primary",        2, 4, 15.0,  60, "road"),
    ("secondary",      3, 4, 13.0,  50, "road"),
    ("tertiary",       4, 2, 10.0,  40, "road"),
    ("residential",    5, 2,  8.0,  30, "road"),
    ("unclassified",   6, 2,  7.5,  30, "road"),
    ("service",        7, 1,  5.0,  20, "road"),
    ("living_street",  8, 2,  6.5,  20, "road"),
    ("pedestrian",     9, 1,  6.0,   8, "foot"),
    ("footway",       10, 1,  2.5,   6, "foot"),
    ("path",          11, 1,  2.0,   6, "foot"),
    ("steps",         12, 1,  2.0,   4, "foot"),
    ("cycleway",      13, 1,  2.5,  20, "foot"),
    ("track",         14, 1,  4.0,  20, "road"),
    ("rail",          15, 2,  9.0,  80, "rail"),
    ("light_rail",    16, 2,  8.0,  60, "rail"),
    ("subway",        17, 2,  8.0,  60, "rail"),
    ("monorail",      18, 1,  6.0,  60, "rail"),
    ("runway",        19, 1, 45.0, 300, "air"),
    ("taxiway",       20, 1, 23.0,  40, "air"),
    ("helipad",       21, 1, 20.0,   0, "air"),
    ("pier",          22, 1,  6.0,  10, "foot"),
    ("ferry",         23, 1,  0.0,  30, "water"),
]
ROAD_ID = {k: i for k, (i, *_rest) in
           ((c[0], (c[1], c[2], c[3], c[4], c[5])) for c in ROAD_CLASSES)}
ROAD_META = {c[0]: {"id": c[1], "lanes": c[2], "width": c[3], "speed": c[4], "kind": c[5]}
             for c in ROAD_CLASSES}

HIGHWAY_ALIAS = {
    "motorway_link": "motorway", "trunk_link": "trunk", "primary_link": "primary",
    "secondary_link": "secondary", "tertiary_link": "tertiary", "road": "unclassified",
    "bridleway": "path", "corridor": "footway", "busway": "secondary",
}

# Land-cover classes; index is the game's surface id.
LANDUSE_CLASSES = [
    "water", "wetland", "mangrove", "beach", "sand", "rock", "forest", "scrub",
    "grass", "park", "farmland", "cemetery", "residential", "commercial",
    "industrial", "retail", "military", "railway_land", "airport_land",
    "construction", "quarry", "pitch", "parking", "port",
]
LANDUSE_ID = {k: i for i, k in enumerate(LANDUSE_CLASSES)}

BUILDING_CLASSES = [
    "generic", "residential", "apartments", "house", "commercial", "office",
    "retail", "industrial", "warehouse", "civic", "school", "hospital",
    "religious", "transport", "hut", "shed", "garage", "construction", "ruins",
    "stadium", "hotel", "tower",
]
BUILDING_ID = {k: i for i, k in enumerate(BUILDING_CLASSES)}

BUILDING_MAP = {
    "yes": "generic", "residential": "residential", "apartments": "apartments",
    "house": "house", "detached": "house", "semidetached_house": "house",
    "bungalow": "house", "terrace": "house", "dormitory": "apartments",
    "commercial": "commercial", "office": "office", "retail": "retail",
    "supermarket": "retail", "kiosk": "retail", "shop": "retail",
    "industrial": "industrial", "factory": "industrial", "manufacture": "industrial",
    "warehouse": "warehouse", "storage_tank": "industrial", "silo": "industrial",
    "civic": "civic", "government": "civic", "public": "civic", "townhall": "civic",
    "courthouse": "civic", "fire_station": "civic", "police": "civic",
    "school": "school", "college": "school", "university": "school",
    "kindergarten": "school", "hospital": "hospital", "clinic": "hospital",
    "church": "religious", "mosque": "religious", "temple": "religious",
    "cathedral": "religious", "chapel": "religious", "synagogue": "religious",
    "shrine": "religious", "religious": "religious", "monastery": "religious",
    "train_station": "transport", "transportation": "transport",
    "hangar": "transport", "terminal": "transport", "bus_station": "transport",
    "hut": "hut", "cabin": "hut", "shack": "hut", "shed": "shed",
    "garage": "garage", "garages": "garage", "carport": "garage",
    "construction": "construction", "ruins": "ruins",
    "stadium": "stadium", "grandstand": "stadium", "sports_hall": "stadium",
    "hotel": "hotel", "tower": "tower", "skyscraper": "tower",
}

# Typical storey height in metres, by building class. Mumbai floor-to-floor for
# residential towers is close to 3.0 m; industrial sheds are taller per level.
LEVEL_HEIGHT = collections.defaultdict(lambda: 3.1, {
    "hut": 2.6, "shed": 2.8, "garage": 2.8, "house": 3.0, "residential": 3.0,
    "apartments": 3.0, "office": 3.7, "commercial": 3.8, "retail": 4.0,
    "industrial": 6.0, "warehouse": 7.0, "stadium": 8.0, "religious": 5.0,
    "transport": 6.0, "hospital": 3.6, "school": 3.5, "tower": 3.2,
})

_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def parse_length(value):
    """OSM heights: '42', '42 m', '42.5', \"137'\", '137 ft'."""
    if not value:
        return None
    v = value.strip().lower()
    m = _NUM.search(v)
    if not m:
        return None
    n = float(m.group())
    if "'" in v or "ft" in v or "feet" in v:
        n *= 0.3048
    if n <= 0 or n > 900:
        return None
    return n


def parse_int(value, lo=1, hi=24):
    if not value:
        return None
    m = _NUM.search(value)
    if not m:
        return None
    try:
        n = int(float(m.group()))
    except ValueError:
        return None
    return n if lo <= n <= hi else None


class Layers:
    """Accumulates projected geometry. Polylines/rings are stored flat."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.road_xy, self.road_off, self.road_attr, self.road_name = [], [0], [], []
        self.bld_xy, self.bld_off, self.bld_attr = [], [0], []
        self.lu_xy, self.lu_off, self.lu_attr = [], [0], []
        self.coast = []            # list of (N,2) float64 arrays, OSM direction
        self.water_xy, self.water_off, self.water_attr = [], [0], []
        self.poi = []              # (x, z, kind, name)
        self.stats = collections.Counter()

    def add_line(self, pts, attr, name=""):
        self.road_xy.extend(pts)
        self.road_off.append(len(self.road_xy))
        self.road_attr.append(attr)
        self.road_name.append(name)

    def add_building(self, ring, attr):
        self.bld_xy.extend(ring)
        self.bld_off.append(len(self.bld_xy))
        self.bld_attr.append(attr)

    def add_landuse(self, ring, attr):
        self.lu_xy.extend(ring)
        self.lu_off.append(len(self.lu_xy))
        self.lu_attr.append(attr)

    def add_water(self, ring, attr):
        self.water_xy.extend(ring)
        self.water_off.append(len(self.water_xy))
        self.water_attr.append(attr)


def project(nodes):
    """osmium node list -> (N,2) float64 of local metres, deduplicated."""
    out = []
    last = None
    for n in nodes:
        if not n.location.valid():
            continue
        x, z = aoi.to_xz(n.location.lat, n.location.lon)
        if last is not None and abs(x - last[0]) < 0.05 and abs(z - last[1]) < 0.05:
            continue
        out.append((x, z))
        last = (x, z)
    return out


def build_handler(L):
    import osmium

    class H(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.t0 = time.time()
            self.seen = 0

        # ---------------- ways ------------------------------------------
        def way(self, w):
            self.seen += 1
            if self.seen % 400000 == 0:
                print(f"  ...{self.seen // 1000}k ways "
                      f"({time.time() - self.t0:.0f}s)", flush=True)
            t = w.tags
            try:
                if "natural" in t and t["natural"] == "coastline":
                    pts = project(w.nodes)
                    if len(pts) >= 2:
                        L.coast.append(np.asarray(pts, dtype=np.float64))
                        L.stats["coastline_ways"] += 1
                    return

                hw = t.get("highway")
                rw = t.get("railway")
                aw = t.get("aeroway")
                rt = t.get("route")
                mw = t.get("man_made")

                key = None
                if hw:
                    key = HIGHWAY_ALIAS.get(hw, hw)
                    if key not in ROAD_META:
                        key = "service" if hw.endswith("_link") else None
                elif rw in ("rail", "light_rail", "subway", "monorail", "narrow_gauge"):
                    key = "rail" if rw == "narrow_gauge" else rw
                elif aw in ("runway", "taxiway", "helipad"):
                    key = aw
                elif mw == "pier":
                    key = "pier"
                elif rt == "ferry":
                    key = "ferry"

                if key:
                    pts = project(w.nodes)
                    if len(pts) >= 2:
                        meta = ROAD_META[key]
                        lanes = parse_int(t.get("lanes"), 1, 16) or meta["lanes"]
                        oneway = 1 if t.get("oneway") in ("yes", "true", "1") else (
                            -1 if t.get("oneway") == "-1" else 0)
                        if key in ("motorway",) and t.get("oneway") is None:
                            oneway = 1
                        width = parse_length(t.get("width"))
                        if width is None:
                            width = (lanes * 3.4 if meta["kind"] == "road"
                                     else meta["width"])
                            width = max(width, 3.0)
                        speed = parse_int(t.get("maxspeed"), 5, 160) or meta["speed"]
                        layer = parse_int(t.get("layer"), -6, 6) or 0
                        bridge = 1 if t.get("bridge") not in (None, "no") else 0
                        tunnel = 1 if t.get("tunnel") not in (None, "no") else 0
                        surface = t.get("surface", "")
                        unpaved = 1 if surface in (
                            "unpaved", "ground", "dirt", "earth", "gravel", "sand",
                            "grass", "compacted", "mud") else 0
                        L.add_line(pts, (meta["id"], lanes, oneway, round(width, 2),
                                         speed, layer, bridge, tunnel, unpaved,
                                         len(pts)), t.get("name", ""))
                        L.stats[f"road_{key}"] += 1
                        if key == "runway":
                            seg = np.asarray(pts)
                            d = float(np.hypot(*(seg[-1] - seg[0])))
                            L.poi.append((float(seg[:, 0].mean()),
                                          float(seg[:, 1].mean()), "runway",
                                          f"{t.get('ref', t.get('name', 'runway'))}"
                                          f" {d:.0f}m"))
                    return

                if w.is_closed() and len(w.nodes) >= 4:
                    self._area(t, w.nodes)
            except Exception:                                      # noqa: BLE001
                L.stats["way_errors"] += 1

        def _area(self, t, nodes):
            b = t.get("building") or t.get("building:part")
            if b and b != "no":
                ring = project(nodes)
                if len(ring) < 4:
                    return
                cls = BUILDING_MAP.get(b, "generic")
                if t.get("amenity") in ("place_of_worship",):
                    cls = "religious"
                h = parse_length(t.get("height")) or parse_length(t.get("building:height"))
                lv = parse_int(t.get("building:levels"), 1, 180)
                if h is not None:
                    src = 2                       # explicit height tag
                elif lv is not None:
                    h = lv * LEVEL_HEIGHT[cls] + 1.2
                    src = 1                       # from building:levels
                else:
                    h = 0.0
                    src = 0                       # unknown; filled from the DEM canopy
                minh = parse_length(t.get("min_height")) or 0.0
                roof = t.get("roof:shape", "")
                L.add_building(ring, (BUILDING_ID[cls], round(h, 2), src,
                                      round(minh, 2),
                                      1 if roof in ("gabled", "hipped", "pyramidal") else 0,
                                      len(ring)))
                L.stats["buildings"] += 1
                L.stats[f"bheight_src_{src}"] += 1
                return

            kind = self._landcover(t)
            if kind is None:
                return
            ring = project(nodes)
            if len(ring) < 4:
                return
            if kind in ("water", "wetland", "mangrove"):
                L.add_water(ring, (LANDUSE_ID[kind], len(ring),
                                   1 if t.get("tidal") == "yes" else 0))
                L.stats[f"water_{kind}"] += 1
            else:
                L.add_landuse(ring, (LANDUSE_ID[kind], len(ring)))
                L.stats[f"land_{kind}"] += 1

        @staticmethod
        def _landcover(t):
            nat = t.get("natural")
            lu = t.get("landuse")
            lei = t.get("leisure")
            amn = t.get("amenity")
            aer = t.get("aeroway")
            wtr = t.get("water")
            if nat == "wetland":
                return "mangrove" if t.get("wetland") in ("mangrove", "tidalflat") \
                    else "wetland"
            if nat in ("water", "bay", "strait") or lu in ("reservoir", "basin") \
                    or t.get("waterway") in ("riverbank", "dock"):
                return "water"
            if wtr:
                return "water"
            if nat == "beach":
                return "beach"
            if nat in ("sand", "shingle"):
                return "sand"
            if nat in ("bare_rock", "scree", "cliff"):
                return "rock"
            if nat in ("wood",) or lu == "forest":
                return "forest"
            if nat in ("scrub", "heath", "grassland"):
                return "scrub"
            if lei in ("park", "garden", "nature_reserve", "common"):
                return "park"
            if lei in ("pitch", "golf_course", "track", "sports_centre", "stadium"):
                return "pitch"
            if lu in ("grass", "meadow", "village_green", "recreation_ground"):
                return "grass"
            if lu in ("farmland", "orchard", "farmyard", "allotments", "plant_nursery",
                      "aquaculture", "salt_pond"):
                return "farmland"
            if lu == "cemetery" or amn == "grave_yard":
                return "cemetery"
            if lu in ("residential",):
                return "residential"
            if lu in ("commercial",):
                return "commercial"
            if lu in ("industrial", "port"):
                return "industrial" if lu == "industrial" else "port"
            if lu == "retail":
                return "retail"
            if lu == "military":
                return "military"
            if lu == "railway":
                return "railway_land"
            if lu in ("construction", "brownfield", "greenfield", "landfill"):
                return "construction"
            if lu in ("quarry",):
                return "quarry"
            if aer in ("aerodrome", "apron", "terminal"):
                return "airport_land"
            if amn == "parking" or t.get("parking"):
                return "parking"
            if t.get("man_made") in ("breakwater", "pier", "groyne"):
                return "rock"
            return None

        # ---------------- relations: multipolygons ----------------------
        def area(self, a):
            try:
                t = a.tags
                if a.from_way():
                    return                      # already handled in way()
                kind = None
                b = t.get("building")
                if b and b != "no":
                    kind = "building"
                else:
                    kind = self._landcover(t)
                if kind is None:
                    return
                for outer in a.outer_rings():
                    ring = project(outer)
                    if len(ring) < 4:
                        continue
                    if kind == "building":
                        cls = BUILDING_MAP.get(b, "generic")
                        h = parse_length(t.get("height"))
                        lv = parse_int(t.get("building:levels"), 1, 180)
                        if h is not None:
                            src = 2
                        elif lv is not None:
                            h, src = lv * LEVEL_HEIGHT[cls] + 1.2, 1
                        else:
                            h, src = 0.0, 0
                        L.add_building(ring, (BUILDING_ID[cls], round(h, 2), src,
                                              0.0, 0, len(ring)))
                        L.stats["buildings_rel"] += 1
                    elif kind in ("water", "wetland", "mangrove"):
                        L.add_water(ring, (LANDUSE_ID[kind], len(ring), 0))
                        L.stats[f"water_{kind}_rel"] += 1
                    else:
                        L.add_landuse(ring, (LANDUSE_ID[kind], len(ring)))
                        L.stats[f"land_{kind}_rel"] += 1
            except Exception:                                      # noqa: BLE001
                L.stats["area_errors"] += 1

        # ---------------- nodes: named places and landmarks -------------
        def node(self, n):
            t = n.tags
            kind = None
            if t.get("place") in ("suburb", "neighbourhood", "quarter", "town",
                                  "village", "city", "locality", "island"):
                kind = "place:" + t["place"]
            elif t.get("railway") == "station":
                kind = "station"
            elif t.get("aeroway") == "aerodrome":
                kind = "aerodrome"
            elif t.get("amenity") == "ferry_terminal":
                kind = "ferry_terminal"
            elif t.get("natural") == "peak":
                kind = "peak"
            if kind and t.get("name"):
                x, z = aoi.to_xz(n.location.lat, n.location.lon)
                L.poi.append((x, z, kind, t["name"]))
                L.stats["poi"] += 1

    return H()


def classify_coastline(coast_lines, aoi_polygon_xz):
    """
    Turn OSM coastline ways into land polygons.

    OSM ships no land polygons. It ships ``natural=coastline`` ways, oriented so
    that land is on the left of the direction of travel. To get areas out of
    that: noodle the ways together, clip them to the AOI, close the result
    against the AOI boundary, polygonize, and then classify each face by
    stepping a short distance to the left of a coastline segment on its own
    boundary and asking which face that point lands in.

    Closing against the AOI boundary specifically, rather than a bounding box
    around it, is the part that matters. Mumbai's coastline arrives as a single
    189 km open string whose ends stop where the extract was cut. Against a box
    drawn outside that cut, the string closes nothing, polygonize returns one
    face covering everything, and the island silently ceases to be an island.
    """
    from shapely.geometry import LineString, Polygon, Point, MultiLineString
    from shapely.ops import linemerge, unary_union, polygonize
    from shapely.strtree import STRtree

    if not coast_lines:
        raise SystemExit("FATAL: the extract contains no natural=coastline ways. "
                         "Without them there is no land/sea boundary.")

    poly = Polygon(aoi_polygon_xz)
    lines = [LineString(c) for c in coast_lines if len(c) >= 2]
    merged = linemerge(unary_union(lines))
    clipped = linemerge(unary_union(merged.intersection(poly)))
    if isinstance(clipped, LineString):
        clipped = MultiLineString([clipped])
    if clipped.is_empty:
        raise SystemExit("FATAL: no coastline survives the AOI clip")
    print(f"  {len(lines)} coastline ways -> {len(clipped.geoms)} strings inside "
          f"the AOI, longest "
          f"{max(g.length for g in clipped.geoms) / 1000:.0f} km")

    network = unary_union([clipped, poly.exterior])
    faces = [f for f in polygonize(network) if f.area > 1.0]
    if not faces:
        raise SystemExit("FATAL: the coastline did not partition the AOI")
    covered = sum(f.area for f in faces) / poly.area
    print(f"  polygonized into {len(faces)} faces covering "
          f"{covered * 100:.1f}% of the AOI")
    if covered < 0.98:
        raise SystemExit(f"FATAL: faces cover only {covered * 100:.1f}% of the AOI; "
                         "the coastline did not close")

    tree = STRtree(faces)
    land_votes = collections.Counter()
    probes = 0
    for line in clipped.geoms:
        cs = np.asarray(line.coords)
        if len(cs) < 2:
            continue
        step = max(1, len(cs) // 80)
        for i in range(0, len(cs) - 1, step):
            ax, az = cs[i]
            bx, bz = cs[i + 1]
            dx, dz = bx - ax, bz - az
            ln = float(np.hypot(dx, dz))
            if ln < 1e-6:
                continue
            mx, mz = (ax + bx) * 0.5, (az + bz) * 0.5
            # This frame is x east, z south, which is a left-handed plane. Map
            # the usual (-dy, dx) left normal through y = -z and it comes out as
            # (dz, -dx). Verified against Marine Drive, Back Bay and Thane Creek.
            nx, nz = dz / ln, -dx / ln
            for sign, bucket in ((1.0, "L"), (-1.0, "R")):
                p = Point(mx + sign * 0.8 * nx, mz + sign * 0.8 * nz)
                for idx in tree.query(p):
                    if faces[int(idx)].contains(p):
                        land_votes[(int(idx), bucket)] += 1
                        break
            probes += 1

    land_idx, sea_idx = [], []
    for i in range(len(faces)):
        left = land_votes.get((i, "L"), 0)
        right = land_votes.get((i, "R"), 0)
        if left == 0 and right == 0:
            continue
        (land_idx if left > right else sea_idx).append(i)

    land = unary_union([faces[i] for i in land_idx]) if land_idx else None
    if land is None or land.is_empty:
        raise SystemExit("FATAL: coastline classification produced no land")
    land_km2 = land.area / 1e6
    sea_km2 = sum(faces[i].area for i in sea_idx) / 1e6
    print(f"  {probes} probes -> {len(land_idx)} land faces ({land_km2:.1f} km2), "
          f"{len(sea_idx)} sea faces ({sea_km2:.1f} km2)")
    if land_km2 < 400:
        raise SystemExit(f"FATAL: only {land_km2:.1f} km2 of land. Salsette alone is "
                         "about 430 km2, so the classification is inverted or the "
                         "coastline did not close.")
    if sea_km2 < 200:
        raise SystemExit(f"FATAL: only {sea_km2:.1f} km2 of sea; the classification "
                         "looks inverted.")
    return land


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pbf", default="data/osm/mumbai-aoi.osm.pbf")
    ap.add_argument("--out", default="data/layers")
    args = ap.parse_args()

    import osmium
    os.makedirs(args.out, exist_ok=True)
    L = Layers()
    h = build_handler(L)
    t0 = time.time()
    print(f"[extract] reading {args.pbf}", flush=True)
    h.apply_file(args.pbf, locations=True, idx="flex_mem")
    print(f"[extract] pass complete in {time.time() - t0:.0f}s", flush=True)

    print("[extract] assembling land polygons from the OSM coastline")
    ring_xz = [aoi.to_xz(lat, lon) for (lon, lat) in aoi.AOI_POLYGON]
    land = classify_coastline(L.coast, ring_xz)

    # ---- serialise ---------------------------------------------------------
    def flat(xy):
        return np.asarray(xy, dtype=np.float64).reshape(-1, 2) if xy \
            else np.zeros((0, 2), dtype=np.float64)

    from shapely.geometry import mapping
    land_geo = mapping(land)
    rings = []
    if land_geo["type"] == "Polygon":
        polys = [land_geo["coordinates"]]
    else:
        polys = land_geo["coordinates"]
    land_xy, land_off, land_role = [], [0], []
    for poly in polys:
        for ri, ring in enumerate(poly):
            land_xy.extend(ring)
            land_off.append(len(land_xy))
            land_role.append(0 if ri == 0 else 1)      # 0 outer, 1 hole
    rings = len(land_role)

    out = os.path.join(args.out, "layers.npz")
    np.savez_compressed(
        out,
        road_xy=flat(L.road_xy).astype(np.float32),
        road_off=np.asarray(L.road_off, dtype=np.int64),
        road_attr=np.asarray(L.road_attr, dtype=np.float32) if L.road_attr
        else np.zeros((0, 10), np.float32),
        bld_xy=flat(L.bld_xy).astype(np.float32),
        bld_off=np.asarray(L.bld_off, dtype=np.int64),
        bld_attr=np.asarray(L.bld_attr, dtype=np.float32) if L.bld_attr
        else np.zeros((0, 6), np.float32),
        lu_xy=flat(L.lu_xy).astype(np.float32),
        lu_off=np.asarray(L.lu_off, dtype=np.int64),
        lu_attr=np.asarray(L.lu_attr, dtype=np.float32) if L.lu_attr
        else np.zeros((0, 2), np.float32),
        water_xy=flat(L.water_xy).astype(np.float32),
        water_off=np.asarray(L.water_off, dtype=np.int64),
        water_attr=np.asarray(L.water_attr, dtype=np.float32) if L.water_attr
        else np.zeros((0, 3), np.float32),
        land_xy=flat(land_xy).astype(np.float32),
        land_off=np.asarray(land_off, dtype=np.int64),
        land_role=np.asarray(land_role, dtype=np.int8),
    )
    with open(os.path.join(args.out, "poi.json"), "w", encoding="utf-8") as fh:
        json.dump([{"x": round(x, 1), "z": round(z, 1), "kind": k, "name": n}
                   for (x, z, k, n) in L.poi], fh)
    with open(os.path.join(args.out, "road_names.json"), "w", encoding="utf-8") as fh:
        json.dump(L.road_name, fh)

    summary = {
        "source_pbf": args.pbf,
        "roads": len(L.road_attr),
        "road_points": len(L.road_xy),
        "buildings": len(L.bld_attr),
        "building_points": len(L.bld_xy),
        "landuse": len(L.lu_attr),
        "water_areas": len(L.water_attr),
        "coastline_ways": len(L.coast),
        "land_rings": rings,
        "land_area_km2": round(land.area / 1e6, 2),
        "poi": len(L.poi),
        "seconds": round(time.time() - t0, 1),
        "detail": dict(sorted(L.stats.items())),
    }
    with open(os.path.join(args.out, "extract_summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=1)
    print(json.dumps({k: v for k, v in summary.items() if k != "detail"}, indent=1))
    print(f"[extract] wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB)")

    if summary["buildings"] < 100000:
        raise SystemExit(f"FATAL: only {summary['buildings']} buildings; Mumbai has "
                         "well over a million mapped in OSM")
    if summary["roads"] < 50000:
        raise SystemExit(f"FATAL: only {summary['roads']} ways; too few for the AOI")
    print("[extract] OK")


if __name__ == "__main__":
    main()
