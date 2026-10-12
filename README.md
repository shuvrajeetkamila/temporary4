# Mumbai: The Island City

A 1:1 scale, fully rendered 3D Mumbai for Android — from Vasai Creek in the north to
Navy Nagar in the south, the Arabian Sea in the west to the western bank of Thane
Creek in the east. Walk it, drive it, sail it, fly it. Entirely offline, no account,
no ads, no network calls, no imagery: every building, road and wave is generated on
the device from 260 KB of packaged geographic data.

> **Download:** the latest APK is on the
> [Releases page](https://github.com/shuvrajeetkamila/temporary4/releases/latest).
> Install it on an Android 8.0+ phone and allow "install unknown apps" when prompted.

---

## What is actually in it

| | |
|---|---|
| **Area** | 44.3 × 65.3 km, the whole Salsette landmass plus a 6 km sea and airspace buffer |
| **Scale** | True metres. Double-precision world coordinates, a floating origin that rebases every kilometre, and a local ENU projection off the WGS84 ellipsoid. Latitude and longitude never reach the physics. |
| **Rendering** | OpenGL ES 3.0, written for this project. Terrain, buildings, roads, bridges, vegetation and vehicles are all generated at runtime. No satellite imagery, no Street View, no third-party 3D data. |
| **Streaming** | 44,544 chunks in a three-level LOD pyramid (256 m / 1 km / 4 km), decoded on worker threads, uploaded to the GPU under a per-frame budget, evicted under a memory budget, with preload rings that grow with your speed and altitude. |
| **Physics** | Fixed 60 Hz, max 4 substeps, render interpolation. Five independent force-based systems. |
| **Offline** | The app declares no permissions and opens no sockets. |

### Five ways to move

* **On foot** — capsule controller with walk, run, swim and terrain following.
* **Car** — per-wheel spring-damper suspension, an engine torque curve, a real gearbox
  with clutch slip, combined-slip tyre forces, wet-weather grip loss, and left-hand
  traffic.
* **Boat** — buoyancy sampled at twelve hull points against the *same* analytic wave
  trains the water shader draws, plus tide, current, planing lift with its matching
  induced drag, and beaching.
* **Helicopter** — collective and cyclic, blade-element thrust with an induced-inflow
  solve, tail-rotor torque, translational lift, ground effect and autorotation.
* **Aeroplane** — q = ½ρv², Cl/Cd curves with a real stall break, flaps, retractable
  gear, and published runway lengths. There is no hidden takeoff impulse; if you do
  not reach rotation speed you do not fly.

Every one of these is force-and-torque based. Nothing assigns a transform or a
velocity to fake motion.

---

## Honest limits

Stated here rather than discovered later:

* **No real-device performance numbers are claimed.** The frame caps (30 FPS default,
  20 in ECO, 15 under severe thermal load) and the work reductions behind them are
  implemented and unit-tested, but no FPS or thermal figure from a physical handset
  was ever measured. The only hardware this has run on is a CI emulator doing software
  rendering, and that number means nothing for your phone.
* **Ambient traffic and pedestrians are kinematic.** They follow the road graph with a
  car-following model. Only the vehicle you are driving runs the full force-based
  simulation. This is a budget decision, not an oversight.
* **Terrain elevation is synthetic.** Named hills are at their real heights and
  locations; everything between them is a model. See `DATA_SOURCES.md`.
* **The coastline is hand-authored, not surveyed.** Typically ±0.3–2 km. The Back Bay /
  Colaba western shore is about 1 km east of where it should be. Documented, not hidden.
* **Collision is a height field plus per-building boxes.** No mesh-accurate collision,
  no vehicle-to-vehicle contacts.
* **No audio.**
* No shadow pass yet.

---

## Verification

The build will not publish an APK unless all of this passes:

| Gate | What it proves |
|---|---|
| `tools/verify_world.py` | 28 geographic gates, re-parsing the packaged `.mwl` files with an independent Python reader: extent, 24 inhabited checkpoints are land, 8 water checkpoints are water, the island is continuous from Colaba to Dahisar, the northern and eastern suburbs are built up, hill heights, chunk-index agreement, runway lengths |
| `:core` unit tests | 84 tests across nine suites: world coverage, all five physics systems, projection, floating-origin rebase, the packaged world, the streaming state machine, the fixed-step loop, procedural meshes, and a headless startup smoke test |
| APK validation | zip integrity, `classes.dex` present, `assets/world/` present, `aapt2 dump badging`, `apksigner verify` |
| **Emulator run** | The APK is installed on a real Android emulator and launched. The job asserts: no fatal exception, no ANR, process alive, GL context created, the packaged world parsed on-device, every shader linked, `glGetError` clean, world tiles resident on the GPU, and the steady-state frame drawing more than 5,000 triangles |

That last gate is the difference between "it compiles" and "it runs", and it has
already earned its keep — it caught a GLSL precision mismatch that made every shader
program fail to link, which no amount of unit testing would have found.

---

## Building it yourself

```sh
# rebuild the world from source coordinates (deterministic)
python3 tools/build_world.py
python3 tools/verify_world.py

# tests and APK
./gradlew :core:test
./gradlew :android:assembleDebug
```

Type-check the Android sources without an SDK:

```sh
ecj -d /tmp/stubout $(find tools/androidstub -name '*.java')
ecj -cp core-classes:/tmp/stubout -d /tmp/aout $(find android/src/main/java -name '*.java')
```

---

## Layout

```
core/          pure Java simulation - no Android imports, runs on a desktop JVM
  world/       .mwl reader, land use, mesh + chunk builders, streamer, water model
  physics/     rigid body, atmosphere, collision
  vehicle/     car, boat, helicopter, aeroplane
  human/       capsule controller
  geo/         WGS84 projection
  game/        engine, camera, floating origin, agents, telemetry, quality tiers
android/       GLSurfaceView host, GLES 3.0 renderer, shaders, HUD, touch controls
tools/         world compiler, format, verifier, previewer, Android API stubs
ci/            device smoke test
```

---

## Documents

* [`DATA_SOURCES.md`](DATA_SOURCES.md) — where every coordinate came from and how
  accurate it is not
* [`ASSET_LICENSES.md`](ASSET_LICENSES.md) — everything shipped is generated by this
  project; no textures, audio, imported models or branded vehicles
* [`docs/TECH_STACK_DECISIONS.md`](docs/TECH_STACK_DECISIONS.md) — why this is a
  hand-written engine rather than libGDX, and what that costs

No OpenStreetMap data is used, so no OSM attribution is displayed. An optional
importer is provided in `tools/osm_import.py` if you want to add it yourself — doing
so makes the ODbL attribution your responsibility.

Place names are ordinary geographic names. Landmarks are generic massing at
public-map level of detail. Vehicles are fictional and unbranded. Informal
settlements, including Dharavi, are rendered as the dense low-rise neighbourhoods
they are, with the same materials and lighting as everywhere else.
