#!/usr/bin/env python3
"""
The area of interest for MUMBAI: THE ISLAND CITY, and the metre projection
everything else in the project uses.

The AOI is a *clipping boundary*, not a depiction of the coastline. It is drawn
generously offshore so that the real OpenStreetMap coastline — which is what
actually defines land and water in this game — is captured complete, together
with a navigable sea and airspace buffer. The boundary follows the physical
geography the brief specifies:

  north  the southern bank and mouth of Vasai Creek
  south  Colaba and Navy Nagar
  west   the Arabian Sea, with a buffer offshore
  east   the western bank of Thane Creek and Mumbai Harbour

Mainland Navi Mumbai, east of Thane Creek, is deliberately left outside the
high-detail polygon. It is picked up only by the wider ``CONTEXT_BOUNDS`` box and
rendered as distant low-detail terrain.
"""

import json
import math
import os

AOI_NAME = "Mumbai / Salsette island"
AOI_VERSION = "2.0.0"

# Geodetic origin of the local ENU frame. Roughly the centroid of the island, so
# float error in the render frame is smallest where the player usually is.
ORIGIN_LAT = 19.0760
ORIGIN_LON = 72.8777

# Navigable water / airspace buffer kept outside the land boundary, metres.
WATER_BUFFER_M = 8000.0

# WGS84.
WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
WGS84_B = WGS84_A * (1.0 - WGS84_F)

# ---------------------------------------------------------------------------
# The clipping polygon, lon/lat, counter-clockwise, closed by the loader.
#
# Vertices are placed offshore of the real coast on every water-facing edge so
# that no real coastline, creek bank, island or jetty is cut off by the clip.
# The eastern edge runs down the middle of Thane Creek, so the western bank and
# its mangroves are inside and the Navi Mumbai shore is not.
# ---------------------------------------------------------------------------
AOI_POLYGON = [
    # --- north-west: Arabian Sea off the Vasai Creek mouth -----------------
    (72.600, 19.470),
    (72.700, 19.480),
    # --- north: across the mouth of Vasai Creek and up the creek line ------
    (72.780, 19.478),
    (72.830, 19.465),
    (72.880, 19.455),
    (72.930, 19.448),
    (72.990, 19.440),
    # --- north-east: head of Thane Creek / Ulhas confluence ----------------
    (73.040, 19.420),
    (73.060, 19.380),
    # --- east: down the middle of Thane Creek ------------------------------
    (73.045, 19.320),
    (73.030, 19.260),
    (73.020, 19.200),
    (73.010, 19.150),
    (73.005, 19.100),
    (73.000, 19.050),
    # --- south-east: into Mumbai Harbour, west of Elephanta ----------------
    (72.990, 19.000),
    (72.985, 18.950),
    (72.975, 18.900),
    (72.960, 18.860),
    # --- south: open water south of Navy Nagar -----------------------------
    (72.900, 18.830),
    (72.820, 18.825),
    (72.760, 18.840),
    # --- west: up the Arabian Sea coast, well offshore ---------------------
    (72.720, 18.900),
    (72.700, 18.980),
    (72.690, 19.060),
    (72.670, 19.140),
    (72.650, 19.220),
    (72.630, 19.300),
    (72.610, 19.390),
]

# Everything the detailed world needs, as a bounding box (minlon, minlat, maxlon, maxlat).
AOI_BOUNDS = (72.600, 18.825, 73.060, 19.480)

# A wider box used only for distant context terrain: the mainland skyline east of
# Thane Creek and the hills behind it. Never populated with buildings or roads.
CONTEXT_BOUNDS = (72.40, 18.65, 73.35, 19.70)

# ---------------------------------------------------------------------------
# Mandatory geographic checkpoints. The build fails if the packaged world does
# not contain real data at every one of these. They are grouped exactly as the
# brief requires, so a missing group names itself in the failure message.
# ---------------------------------------------------------------------------
CHECKPOINTS = [
    # group, key, label, lat, lon, expect ("land" | "water")
    ("vasai_creek", "vasai_mouth", "Vasai Creek mouth", 19.3950, 72.7750, "water"),
    ("vasai_creek", "vasai_east", "Vasai Creek upper reach", 19.3800, 72.8450, "water"),
    ("vasai_creek", "vasai_south_bank", "Vasai Creek south bank", 19.3600, 72.8500, "land"),

    ("north_salsette", "mira_road", "Mira Road / Bhayandar side", 19.2900, 72.8700, "land"),
    ("north_salsette", "gorai", "Gorai", 19.2400, 72.7900, "land"),
    ("north_salsette", "uttan", "Uttan coast", 19.2700, 72.7700, "land"),

    ("northern_belt", "dahisar", "Dahisar", 19.2500, 72.8600, "land"),
    ("northern_belt", "borivali", "Borivali", 19.2300, 72.8570, "land"),
    ("northern_belt", "kandivali", "Kandivali", 19.2050, 72.8500, "land"),
    ("northern_belt", "malad", "Malad", 19.1860, 72.8480, "land"),
    ("northern_belt", "goregaon", "Goregaon", 19.1640, 72.8490, "land"),

    ("national_park", "sgnp", "Sanjay Gandhi National Park", 19.2150, 72.9100, "land"),
    ("national_park", "kanheri", "Kanheri ridge", 19.2080, 72.9050, "land"),
    ("national_park", "tulsi", "Tulsi Lake", 19.1570, 72.9070, "water"),
    ("national_park", "vihar", "Vihar Lake", 19.1430, 72.9090, "water"),
    ("national_park", "aarey", "Aarey", 19.1600, 72.8800, "land"),

    ("thane_creek", "thane_creek_north", "Thane Creek north", 19.1900, 72.9850, "water"),
    ("thane_creek", "thane_creek_mid", "Thane Creek mid", 19.0900, 72.9750, "water"),
    ("thane_creek", "thane_creek_mangrove", "Thane Creek mangroves", 19.1200, 72.9550, "land"),

    ("eastern_suburbs", "mulund", "Mulund", 19.1720, 72.9560, "land"),
    ("eastern_suburbs", "bhandup", "Bhandup", 19.1430, 72.9370, "land"),
    ("eastern_suburbs", "powai", "Powai", 19.1180, 72.9050, "land"),
    ("eastern_suburbs", "vikhroli", "Vikhroli", 19.1050, 72.9280, "land"),
    ("eastern_suburbs", "ghatkopar", "Ghatkopar", 19.0860, 72.9080, "land"),
    ("eastern_suburbs", "chembur", "Chembur", 19.0520, 72.8990, "land"),
    ("eastern_suburbs", "trombay", "Trombay", 19.0010, 72.9200, "land"),

    ("airports", "csmia", "CSMIA", 19.0890, 72.8680, "land"),
    ("airports", "juhu_aero", "Juhu Aerodrome", 19.0980, 72.8340, "land"),

    ("western_suburbs", "andheri", "Andheri", 19.1190, 72.8470, "land"),
    ("western_suburbs", "juhu", "Juhu", 19.1070, 72.8270, "land"),
    ("western_suburbs", "versova", "Versova", 19.1350, 72.8130, "land"),
    ("western_suburbs", "madh", "Madh Island", 19.1400, 72.7950, "land"),

    ("bandra_bkc", "bandra", "Bandra", 19.0590, 72.8300, "land"),
    ("bandra_bkc", "bkc", "Bandra Kurla Complex", 19.0660, 72.8690, "land"),
    ("bandra_bkc", "kurla", "Kurla", 19.0720, 72.8790, "land"),
    ("bandra_bkc", "dharavi", "Dharavi", 19.0430, 72.8520, "land"),
    ("bandra_bkc", "mahim", "Mahim", 19.0400, 72.8400, "land"),

    ("central_mumbai", "worli", "Worli", 19.0000, 72.8170, "land"),
    ("central_mumbai", "dadar", "Dadar", 19.0180, 72.8430, "land"),
    ("central_mumbai", "lower_parel", "Lower Parel", 18.9950, 72.8300, "land"),
    ("central_mumbai", "sewri", "Sewri", 18.9950, 72.8600, "land"),

    ("south_mumbai", "marine_drive", "Marine Drive", 18.9430, 72.8230, "land"),
    ("south_mumbai", "fort", "Fort / CSMT", 18.9400, 72.8350, "land"),
    ("south_mumbai", "docks", "Mumbai docks", 18.9500, 72.8450, "land"),
    ("south_mumbai", "colaba", "Colaba", 18.9100, 72.8150, "land"),
    ("south_mumbai", "navy_nagar", "Navy Nagar", 18.8980, 72.8120, "land"),

    ("arabian_sea", "sea_off_juhu", "Arabian Sea off Juhu", 19.1070, 72.7600, "water"),
    ("arabian_sea", "sea_off_bandra", "Arabian Sea off Bandra", 19.0400, 72.7700, "water"),
    ("arabian_sea", "back_bay", "Back Bay", 18.9250, 72.8080, "water"),

    ("harbour", "harbour_mid", "Mumbai Harbour", 18.9300, 72.9000, "water"),
    ("harbour", "mahim_bay", "Mahim Bay", 19.0330, 72.8180, "water"),
]

CHECKPOINT_GROUPS = sorted({c[0] for c in CHECKPOINTS})


# ---------------------------------------------------------------------------
# Projection: local ENU off the WGS84 ellipsoid, metres.
#
#   x = east, z = south (north is -z), y = up
#
# Identical maths lives in core/.../geo/GeoProjection.java. GeoProjectionTest and
# tools/verify_world.py cross-check the two against each other, because a silent
# divergence here would put the compiled world and the running game in different
# places.
# ---------------------------------------------------------------------------

def meridian_arc(lat_deg):
    """Distance from the equator along the meridian, WGS84 series to e^6."""
    e2 = WGS84_E2
    lat = math.radians(lat_deg)
    c0 = 1.0 - e2 / 4.0 - 3.0 * e2 ** 2 / 64.0 - 5.0 * e2 ** 3 / 256.0
    c2 = 3.0 / 8.0 * (e2 + e2 ** 2 / 4.0 + 15.0 * e2 ** 3 / 128.0)
    c4 = 15.0 / 256.0 * (e2 ** 2 + 3.0 * e2 ** 3 / 4.0)
    c6 = 35.0 * e2 ** 3 / 3072.0
    return WGS84_A * (c0 * lat - c2 * math.sin(2 * lat)
                      + c4 * math.sin(4 * lat) - c6 * math.sin(6 * lat))


_M0 = meridian_arc(ORIGIN_LAT)


def prime_vertical(lat_deg):
    s = math.sin(math.radians(lat_deg))
    return WGS84_A / math.sqrt(1.0 - WGS84_E2 * s * s)


def to_xz(lat, lon):
    """Geodetic degrees -> local metres (x east, z south)."""
    rlat = math.radians(lat)
    x = math.radians(lon - ORIGIN_LON) * prime_vertical(lat) * math.cos(rlat)
    z = -(meridian_arc(lat) - _M0)
    return x, z


def to_latlon(x, z):
    """Local metres -> geodetic degrees. Four Newton steps on the meridian arc."""
    target = _M0 - z
    lat = math.degrees(target / (WGS84_A * (1.0 - WGS84_E2 / 4.0)))
    for _ in range(4):
        err = meridian_arc(lat) - target
        # d(arc)/d(lat_deg), using the meridian radius of curvature.
        s = math.sin(math.radians(lat))
        m = WGS84_A * (1.0 - WGS84_E2) / (1.0 - WGS84_E2 * s * s) ** 1.5
        lat -= math.degrees(err / m)
    rlat = math.radians(lat)
    lon = ORIGIN_LON + math.degrees(x / (prime_vertical(lat) * math.cos(rlat)))
    return lat, lon


def aoi_bounds_xz():
    """The AOI polygon's extent in metres: (min_x, min_z, max_x, max_z)."""
    xs, zs = [], []
    for lon, lat in AOI_POLYGON:
        x, z = to_xz(lat, lon)
        xs.append(x)
        zs.append(z)
    return min(xs), min(zs), max(xs), max(zs)


def write_geojson(path):
    ring = list(AOI_POLYGON) + [AOI_POLYGON[0]]
    features = [{
        "type": "Feature",
        "properties": {
            "name": AOI_NAME,
            "version": AOI_VERSION,
            "origin_lat": ORIGIN_LAT,
            "origin_lon": ORIGIN_LON,
            "water_buffer_m": WATER_BUFFER_M,
            "note": "Clipping boundary, drawn offshore of the real coast. Land and "
                    "water are defined by the OSM coastline, not by this polygon.",
        },
        "geometry": {"type": "Polygon", "coordinates": [[list(p) for p in ring]]},
    }]
    for (g, k, lbl, lat, lon, exp) in CHECKPOINTS:
        features.append({
            "type": "Feature",
            "properties": {"name": "checkpoint", "group": g, "key": k,
                           "label": lbl, "expect": exp},
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
        })
    doc = {
        "type": "FeatureCollection",
        "name": "MUMBAI_AOI",
        "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3/CRS84"}},
        "features": features,
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1)
    return path


def point_in_aoi(lon, lat):
    """Ray casting against the clipping polygon."""
    inside = False
    n = len(AOI_POLYGON)
    for i in range(n):
        x1, y1 = AOI_POLYGON[i]
        x2, y2 = AOI_POLYGON[(i + 1) % n]
        if (y1 > lat) != (y2 > lat):
            xint = x1 + (lat - y1) * (x2 - x1) / (y2 - y1)
            if lon < xint:
                inside = not inside
    return inside


def _report():
    minx, minz, maxx, maxz = aoi_bounds_xz()
    print(f"{AOI_NAME} v{AOI_VERSION}")
    print(f"  origin          {ORIGIN_LAT} N, {ORIGIN_LON} E")
    print(f"  lon/lat bounds  {AOI_BOUNDS}")
    print(f"  metre bounds    x [{minx:.0f} .. {maxx:.0f}]  z [{minz:.0f} .. {maxz:.0f}]")
    print(f"  extent          {(maxx - minx) / 1000:.1f} km east-west"
          f"  x  {(maxz - minz) / 1000:.1f} km north-south")
    print(f"  polygon         {len(AOI_POLYGON)} vertices")
    print(f"  checkpoints     {len(CHECKPOINTS)} in {len(CHECKPOINT_GROUPS)} groups")
    outside = [f"{k} ({lat},{lon})" for (_g, k, _l, lat, lon, _e) in CHECKPOINTS
               if not point_in_aoi(lon, lat)]
    if outside:
        raise SystemExit("FATAL: checkpoints outside the AOI polygon: " + ", ".join(outside))
    print("  every checkpoint lies inside the AOI polygon")

    # Round-trip the projection at the four corners and the origin.
    worst = 0.0
    for lat in (18.83, 19.08, 19.48):
        for lon in (72.60, 72.88, 73.06):
            x, z = to_xz(lat, lon)
            la, lo = to_latlon(x, z)
            d = max(abs(la - lat) * 111320.0,
                    abs(lo - lon) * 111320.0 * math.cos(math.radians(lat)))
            worst = max(worst, d)
    print(f"  projection round-trip worst error {worst * 1000:.3f} mm")
    if worst > 0.01:
        raise SystemExit("FATAL: projection round-trip error above 10 mm")


if __name__ == "__main__":
    _report()
    p = write_geojson(os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), "data", "geodata", "MUMBAI_AOI.geojson"))
    print(f"  wrote {p}")
