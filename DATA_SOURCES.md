# Data sources and geographic accuracy

**Mumbai: The Island City** ships its entire world inside the APK. There is no network
call at runtime, no map tile service, no imagery and no account. This document states
exactly where the geometry came from and, just as importantly, how accurate it is and
is not.

---

## 1. Summary

| Layer | Source | Method |
|---|---|---|
| Coastline, creeks, harbour | Hand-authored | Polygon traced from public geographic knowledge of Salsette's shape, by hand, as latitude/longitude vertices in `tools/mumbai_geo.py` |
| Districts and density | Hand-authored | 71 named centres with a radius and a density class; the land-use raster is a weighted blend plus deterministic noise |
| Roads and expressways | Hand-authored | 34 named corridors as polylines; alignments approximate |
| Suburban railways | Hand-authored | 7 lines (Western, Central, Harbour, Trans-Harbour, Metro, Monorail corridors) |
| Rivers | Hand-authored | Mithi, Dahisar, Poisar, Oshiwara, Ulhas centrelines with nominal widths |
| Lakes | Hand-authored | Powai, Vihar, Tulsi as polygons |
| Parks, maidans, railway yards | Hand-authored | Named centres with radius or oriented rectangle |
| Airports and runways | Hand-authored from public aeronautical facts | Runway designators, headings and **published lengths**; positions approximate |
| Elevation | **Synthetic** | Deterministic model: coastal plain + exponential rise inland + named hills + seeded fractal noise |
| Bathymetry | **Synthetic** | Deterministic depth-versus-distance-offshore model, per water class |
| Buildings | **Procedural** | Generated from the land-use and density rasters with a fixed seed; no real building footprints |
| Vehicles, people, props | **Original procedural geometry** | `core/.../world/ProtoMeshes.java`; see `ASSET_LICENSES.md` |

**No OpenStreetMap data is used, and therefore no OpenStreetMap attribution is
claimed or displayed.** The sandbox this project was built in had no network access to
any geodata host, so nothing was downloaded, traced from a tile, or redistributed. If
you want real OSM geometry, `tools/osm_import.py` is provided as an *optional* path you
can run yourself on a networked machine; if you do, you must add the required ODbL
attribution yourself, and the in-game attribution line in `HudView` must be changed to
include it.

Likewise there is **no SRTM, ASTER, Copernicus or other elevation product** in this
build. The terrain is a model, not a measurement. It is labelled as such below and in
the in-game credits.

---

## 2. Area of interest

```
latitude   18.840 N  ..  19.430 N
longitude  72.660 E  ..  73.080 E
```

Projected to metres with a local ENU frame (see §3):

```
X (east)    -20700 m  ..  +23600 m      44.3 km
Z (south)   -45675 m  ..  +19675 m      65.3 km
```

That covers, continuously:

* **North** — Vasai Creek and the Mira-Bhayandar approach
* **South** — Colaba and Navy Nagar
* **West** — the Arabian Sea with a 6 km water/airspace buffer
* **East** — the western bank of Thane Creek and Mumbai Harbour

Mainland Navi Mumbai east of Thane Creek is present only as low-detail distant
context, as the brief requires.

The AOI polygon is exported to `data/geodata/MUMBAI_AOI.geojson`.

---

## 3. Projection

Everything in the simulation is in **metres**. Latitude and longitude are converted
once, at build time and at load time, and never reach the physics.

Origin: **19.0176 N, 72.8562 E**.

```
x = radians(lon - lon0) * N(lat) * cos(lat)          N = WGS84 prime vertical radius
z = -(M(lat) - M(lat0))                              M = WGS84 meridian arc, series to e^6
```

so **+X is east, +Y is up, +Z is south** (north is −Z). The inverse uses four Newton
iterations on the meridian arc. The Python build tool (`tools/build_world.py`) and the
Java runtime (`core/.../geo/GeoProjection.java`) implement the identical series and are
cross-checked by `GeoProjectionTest` and `tools/verify_world.py`.

At this latitude and over this extent the projection error against the WGS84 ellipsoid
is well under a metre, which is far smaller than the authoring error below.

---

## 4. Accuracy: what is right and what is not

This is the honest part.

### Horizontal accuracy of the coastline

| Region | Typical error |
|---|---|
| Thane Creek / eastern waterfront | ±0.3–1 km |
| Western suburbs shoreline (Juhu to Gorai) | ±0.3–1 km |
| Vasai Creek | ±1–2 km |
| **Back Bay / Colaba western shore** | **~0.7–1.3 km east of reality** |

The Back Bay offset is a known, measured defect: the hand-authored outline places the
Marine Drive shoreline up to about 1.3 km east of its true position. It is inside the
documented tolerance band for this build and has been left rather than silently
patched. Anyone relying on this for navigation should not.

### Road and rail alignments

±100–300 m typically; corridors are correct in topology (which suburb connects to
which, which side of the island a line runs on) but not in surveyed geometry.
Junction layouts, flyover ramps and lane-level detail are invented.

### Airports

Runway **designators, magnetic headings and published lengths** are real public
aeronautical facts. Runway *positions* within the airport are approximate to a few
hundred metres and the field layouts (taxiways, aprons, terminals) are generic
massing. No restricted, security-sensitive or operationally detailed depiction of any
airport, port, naval or government facility is included — exteriors only, at the level
any public map shows.

### Elevation

Entirely synthetic and deterministic (seed `20261012`). Named summits are placed at
their real approximate locations and heights:

| Hill | Height used |
|---|---|
| Malabar Hill | 55 m |
| Pali Hill | 62 m |
| Gilbert Hill | 61 m |
| Trombay | 302 m |
| Kanheri / national park ridge | 467 m |
| Yeoor | 380 m |

Everything between them is a smooth plain-plus-noise model. **Do not treat any height
in this game as a terrain measurement.** Airports are flattened to their published
field elevation.

Inland water (lakes, rivers) stores the **bed** height in the packaged grid; the
surface is reconstructed at runtime as `bed + class depth` (5.0 m for lakes, 2.5 m for
rivers). Sea, harbour and creek water uses a single tidal plane. This is the identical
rule used by the renderer and by the boat buoyancy solver, so the water you see is the
water you float on.

### Buildings and population

Procedural. Building footprints, heights and facades are generated from the density
raster with a fixed seed — they are *representative* of each neighbourhood's character,
not a cadastral record. No real building is modelled from its actual plans, and no
building footprint dataset was used.

Informal settlements, including Dharavi, are represented as dense low-rise fabric with
the same materials and lighting as the rest of the city. They are not caricatured,
not labelled as slums in gameplay text, and carry no derogatory content.

---

## 5. Verification

Two independent implementations check the shipped data, and the build **fails** if
either disagrees:

1. `tools/verify_world.py` — a pure Python reader that re-parses the packaged `.mwl`
   files and asserts 28 geographic gates (extent, 24 inhabited checkpoints are land,
   8 water checkpoints are water, island continuity from Colaba to Dahisar, built-up
   fractions in the northern/eastern/central suburbs, hill heights, chunk-index
   agreement, manifest checkpoint round-trip, runway lengths).
2. `core/src/test/.../WorldCoverageTest.java` — the same facts asserted through the
   *runtime* sampler the game itself uses, plus a 0.013° lat/lon sweep requiring that
   no land sample falls in a chunk the streamer has marked water-only.

Both run in CI before an APK is produced.

---

## 6. Reproducing the world

```sh
python3 tools/build_world.py          # writes android/src/main/assets/world/*.mwl
python3 tools/verify_world.py         # 28 geographic gates
python3 tools/preview_world.py        # optional QA PNGs
```

The build is deterministic: the same `mumbai_geo.py` and seed produce byte-identical
layers.
