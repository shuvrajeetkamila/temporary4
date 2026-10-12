# Mumbai: The Island City

A 1:1 scale, fully rendered 3D Mumbai for Android, built from real
OpenStreetMap geometry and Copernicus elevation — not from a procedural city
generator and not from satellite imagery. Walk, drive, sail, fly a helicopter
or fly an aeroplane across the whole of Salsette Island, from Vasai Creek in
the north to Navy Nagar in the south.

**Download:** the latest APK is on the
[Releases page](https://github.com/shuvrajeetkamila/temporary4/releases).

---

## What is actually in it

| | |
|---|---|
| Extent | 52.5 × 72.5 km, Vasai Creek to Colaba, Arabian Sea to the far bank of Thane Creek |
| Land | 1,571.8 km², assembled from 105 OSM coastline ways |
| Buildings | 196,899 real footprints, extruded |
| Roads | 105,095 ways; 13,983 km of drivable centreline in the routing graph |
| Named places | 1,283 |
| Terrain | Copernicus GLO-30, resampled to a 20 m grid |
| Packaged world | 14.6 MB, streamed from 1,592 non-empty 1 km chunks |
| Scale | True metres, double precision, local ENU projection |

Five physical systems, all force-and-torque based: a human on foot, a car with
per-wheel suspension and a slipping clutch, a boat with eight buoyancy points
on the same waves the shader draws, a helicopter with an induced-inflow solve,
and an aeroplane that needs real runway length to get airborne.

Offline. No login, no ads, no network permission, no backend.

---

## Verification

Nothing is published unless all of this passes first:

- **123** world gates in `tools/verify_world.py`, including 51 geographic
  checkpoints, run against the compiled files with an independently derived
  projection.
- **111** core tests in `core/src/test`, including the same 51 checkpoints
  read back through the game's own loader.
- The built APK is then inspected rather than trusted: badging, signature,
  and a check that all four world files really are inside the zip.
- An emulator job installs it, launches it, and fails unless logcat shows the
  world loaded and frames were rendered.

See [`DELIVERY.md`](DELIVERY.md) for what has **not** been measured — in
particular, no frame rate or thermal behaviour on real hardware.

---

## Licensing and attribution

Map data © OpenStreetMap contributors, ODbL 1.0.
Elevation: Copernicus DEM © DLR e.V. / Airbus DS, ESA.

The attribution is displayed in-app on the HUD at all times, as the ODbL
requires. Full detail in [`DATA_SOURCES.md`](DATA_SOURCES.md) and
[`ASSET_LICENSES.md`](ASSET_LICENSES.md). No brands, no logos, no imported
assets, no copyrighted game content.

---

## Layout

```
core/     engine: maths, world format, streaming, five physics models   (no Android dependency)
android/  app: GLES 3 renderer, shaders, HUD, touch controls
tools/    the data pipeline, and the verifier that gates the build
data/     pinned OSM extract and elevation, plus build reports
```

`core` deliberately has no reference to Android or OpenGL, which is why the
whole simulation can be driven from a plain JUnit test against the real
compiled world.

## Building it yourself

```bash
python tools/extract_osm.py --pbf data/pinned/mumbai-aoi.osm.pbf --out data/layers
cp data/pinned/elevation.npz data/dem/elevation.npz
python tools/compile_world.py --layers data/layers --dem data/dem \
                              --out android/src/main/assets/world
python tools/verify_world.py --world android/src/main/assets/world
gradle :core:test -Dmumbai.requireWorld=true
gradle :android:assembleRelease
```
