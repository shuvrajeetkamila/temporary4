# Data sources

Every piece of geometry in this game comes from open map data. Nothing is
traced from a satellite image, nothing is captured from Street View, and no
proprietary dataset is involved.

---

## 1. OpenStreetMap

**What it provides.** All of it: the coastline, the road network, 196,899
building footprints, land use, water bodies, named places and points of
interest.

**Extract.** `asia/india/western-zone-latest.osm.pbf` from Geofabrik, cut to
the area of interest with `osmium extract --strategy complete_ways`. Geofabrik
publishes no state-level India extract — `asia/india/maharashtra-latest.osm.pbf`
is a 404 — so the zone file is the smallest correct source. The clipped extract
is 19.1 MB and is committed at `data/pinned/mumbai-aoi.osm.pbf`.

**Licence.** Open Database License (ODbL) v1.0.
<https://opendatacommons.org/licenses/odbl/1-0/>

**What the ODbL requires of this project, and where each obligation is met.**

| Obligation | Where it is met |
|---|---|
| Attribute the source | `"© OpenStreetMap contributors"` is drawn by the HUD every frame in a fixed position, from the moment the app opens and including while the world is still loading. It is in `android/src/main/res/values/strings.xml` and rendered by `Hud.onDraw`. |
| Keep the attribution visible to the end user | It is part of the game's own HUD, not a buried credits screen. `WorldPresenceTest.attributionIsPresent` fails the build if the string is missing from the compiled world's manifest. |
| Share alike on derived databases | The compiled world in `android/src/main/assets/world/` is a Produced Work derived from an OSM database. The tools that produce it (`tools/extract_osm.py`, `tools/compile_world.py`) are in this repository under the same open terms, so anyone can regenerate the database from the same inputs. |
| State that the data is modified | It is. See *How the data was transformed*, below. |

**Attribution string, verbatim, as shown in-app:**

> Map data © OpenStreetMap contributors (ODbL 1.0) • Elevation: Copernicus DEM
> © DLR e.V. / Airbus DS, ESA

---

## 2. Copernicus DEM GLO-30

**What it provides.** Terrain elevation, and nothing else.

**Source.** Four 1°×1° GLO-30 Digital Surface Model tiles covering
18–20 N, 72–74 E, mosaicked and resampled onto the game's 20 m metric grid.
Committed as `data/pinned/elevation.npz`.

**Licence.** Free, worldwide, non-exclusive, royalty-free, with attribution.
© DLR e.V. (2014–2018) and © Airbus Defence and Space GmbH (2014–2018),
provided under COPERNICUS by the European Space Agency.

**A limitation worth stating plainly.** GLO-30 is a *surface* model: it
includes buildings and trees. An attempt was made to recover building heights
from it by morphological opening — subtract a heavily eroded version of the
surface from the surface itself and what is left should be the things standing
on the ground. It does not work in Mumbai. A grey opening with a 120 m disk
only removes features narrower than about 240 m, and the built-up area is
kilometres across, so the "canopy" collapses to nothing: median 0.0 m, 95th
percentile 5.7 m, and 139,793 of 167,369 buildings fell to the floor value.
Widening the structuring element far enough to work would gouge the Sanjay
Gandhi National Park hills, which are real terrain. **Copernicus data is
therefore used for terrain only. It contributes nothing to building heights.**

---

## 3. How the data was transformed

The pipeline is four stages, all in `tools/`, all reproducible.

1. **`fetch_osm.py`** — scrapes the Geofabrik index, probes for the smallest
   extract that covers Mumbai, downloads it, checks the published md5, and
   clips to the AOI polygon.
2. **`fetch_dem.py`** — fetches the four GLO-30 tiles, mosaics them, and
   resamples bilinearly onto the metric grid.
3. **`extract_osm.py`** — classifies ways and relations into 24 road classes,
   24 land-use classes and 22 building classes; assembles land polygons from
   the raw coastline.
4. **`compile_world.py`** — rasterises terrain and land cover, cuts the world
   into 1024 m chunks, builds the routing graph, and writes the four MWL2
   container files that ship in the APK.

### Assembling land from the coastline

OSM does not publish "land". It publishes a coastline as directed ways with
land on the left, and the land has to be reconstructed. The main Mumbai
coastline string is 197 km long and *open*: its ends stop at the edge of the
extract. Closing it against a padded bounding box produces one enormous
polygon plus slivers, and about 3.5 km² of sea is labelled land. Closing it
against the AOI polygon itself is what works:

```python
clipped = linemerge(unary_union(merged.intersection(aoi_polygon)))
faces   = polygonize(unary_union([clipped, aoi_polygon.exterior]))
```

Each resulting face is then voted land or sea by probing 0.8 m either side of
the segment midpoints that bound it. 105 coastline ways become 26 strings,
then 24 faces: 23 land totalling 1,571.8 km², one sea of 1,251.0 km², covering
100.0 % of the area of interest.

### Inland water

Lakes inside the land mask have to be subtracted explicitly, or Powai, Vihar
and Tulsi are solid ground you can walk across. 187,586 cells — 75.0 km² — are
cleared this way, taking the land fraction from 41.2 % to its final value.

### Building heights, and how honest they are

This is the part of the data most worth being careful about.

| Source | Buildings | Share |
|---|---:|---:|
| OSM `height` tag | 20,582 | 10.5 % |
| OSM `building:levels` × 3.1 m | 7,363 | 3.8 % |
| Median of OSM-tagged buildings within the same 300 m cell | 134,171 | 68.5 % |
| Class default by building type | 33,627 | 17.2 % |

**So 14.3 % of building heights come from OpenStreetMap tags. The other
85.7 % are inferred.** The neighbourhood prior blends 0.68 of the local median
with 0.32 of the class default, then clamps to the class range, to
`5.0 + 3.1·√area`, and finally to [2.4, 420] m. The split is recorded in the
compiled world's `cmeta.height_src` and reported at delivery. The skyline is
plausible and locally consistent; it is not surveyed.

### Known divergences from the ground truth

- **The upper reaches of Thane Creek and Vasai Creek are mapped in OSM as
  intertidal mangrove and wetland, not open water, and are modelled here as
  land.** That is geographically correct — those are the flamingo mudflats —
  but it means you cannot sail all the way up either creek. OSM's coastline in
  the 19.30–19.40 N band stops at 72.827 E; east of that it is 13.6 km² of
  mangrove and 10.8 km² of wetland.
- Navi Mumbai, east of Thane Creek, is carried as distant low-detail context
  only. The brief asks for the Salsette landmass; the mainland is scenery.

---

## 4. Why `data/pinned/` exists

The sandbox this was built in has no route to any map host: Geofabrik,
Overpass, planet.osm, BBBike and the Copernicus S3 bucket are all unreachable.
The clipped OSM extract and the resampled elevation grid are therefore
committed to the repository so that CI — which *can* reach the network, but
should not re-download 221 MB on every run — has a stable, hash-checked input.
`data/pinned/SHA256SUMS` is verified as the first step of every build.

These are the only binary data files in the repository. The compiled world,
which is 14.6 MB, is built fresh by CI on every run and is never committed.

---

## 5. What is *not* used

No Google Maps, Google Earth or Street View data, imagery or tiles. No Apple
or Bing map data. No satellite or aerial photography of any kind: the world is
fully rendered 3D geometry built from vectors and a height field, and there is
no image of the real city anywhere in the APK. No commercial 3D city model. No
game assets from any existing title.
