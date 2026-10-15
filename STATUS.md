# Status — what is built, what is not

Current release **v2.0.38** (CI run `38618818277`). 18,321 lines of Java
across 45 core classes and 11 Android classes, 44 test files holding
**455 core tests + 24 Android tests**, and **145 world verification
gates**. All green.

This file is the honest inventory. Anything not listed under "Built" is
not built, whatever the commit messages may sound like.

---

## Built

### 1. The world — real Mumbai, from real data

| | |
|---|---|
| Source | OpenStreetMap (ODbL) + Copernicus GLO-30 elevation, both pinned in `data/pinned/` |
| Coverage | Vasai Creek to Colaba, Arabian Sea to Thane Creek — the whole Salsette landmass |
| Land | 1,571.76 km², 8 km water/airspace buffer |
| Roads | 105,117 ways, 13,984 km of driveable graph (370,546 nodes / 352,391 edges) |
| Buildings | 196,899 footprints (195,743 meshed) |
| Places | 1,283 named POI |
| Transit | 443 routes, 1,690 km |
| Craft | 89 spawns — 56 boats, 27 helicopters, 6 aeroplanes |
| Container | 3,869 chunks (53 wide), 1,592 non-empty, 14.6 MB, per-chunk deflate |

- True 1:1 metres, double-precision global coordinates, local ENU
  projection. Latitude/longitude never reaches physics.
- Floating origin with rebase tests.
- 51 geographic checkpoints assert coverage; the build fails if a region
  goes missing.
- 145 independent verification gates (`tools/verify_world.py`) run in CI
  before anything is packaged.

### 2. Streaming

Full state machine — `UNLOADED → QUEUED_IO → DECODING → READY_CPU →
QUEUED_GPU → ACTIVE_HIGH/MED/LOW → EVICTING`. Worker-thread decode,
bounded per-frame GPU upload, cancellation, eviction with a grace
period, speed-aware preload rings, and a rebuild path that never takes
a tile off screen while its replacement is being built.

### 3. Physics — five systems, all force and torque

| System | What is modelled |
|---|---|
| **Human** | Capsule controller, walk/run/swim, ground probe, baton |
| **Car** | Per-wheel spring-damper, torque curve, clutch, gearbox, slip curves, wet grip, left-hand traffic |
| **Boat** | 6–12 buoyancy points, analytic waves shared with the renderer, tide, planing, beaching |
| **Helicopter** | Collective/cyclic, tail rotor torque, translational lift, ground effect, autorotation |
| **Aeroplane** | q = ½ρv², Cl/Cd curves, stall, flaps, gear, real runway lengths, no hidden impulse |

Fixed 60 Hz accumulator, max 4 substeps, dt clamped to 0.1 s, render
interpolation. SI units throughout.

Measured: sedan 198 km/h, 0–60 in 6.0 s, 100–0 in 44 m at 0.90 g;
kaali-peeli 135/6.8/13.2; autorickshaw 65; double-decker 74; scooter
85 km/h at 0.62 g; autorotation 11.1 → 7.3 m/s; takeoff roll 894 m
clean, 591 m at flap 25°.

### 4. Rendering

- Five GLSL ES 3.0 programs: terrain/buildings, instanced bodies, sea,
  sky, rain. All verified to link on-device in CI.
- 15-layer procedural texture array (128×128 RGBA8), no imported art.
- Terrain at 32/16/8 subdivisions by distance, with skirts that close
  the LOD seam (measured tear was up to 137 m).
- One water surface: the analytic sea, masked against a land texture so
  it stops at the coast, with per-pixel wave normals faded by distance.
- Time of day on a 24-minute cycle, golden hour, emissive night windows,
  monsoon rain, wet roads, blob shadows, distance fog.
- Thermal tiers LOW/BALANCED/HIGH/ECO/ECO_SEVERE that cut resolution,
  radii, agent caps and sea tessellation — not just the frame cap.

### 5. The game

- **Core loop:** walk up to any parked vehicle, get in, drive, get out.
  Same for moored boats, parked helicopters and aeroplanes.
- **Vehicles:** kaali-peeli taxi, autorickshaw, BEST-style double-decker,
  tempo, scooter, generic cars — all original, unbranded.
- **Traffic** that yields, brakes and can be crashed into; **pedestrians**
  that scatter from real threats only.
- **Police:** patrol cars and officers on foot, five-star wanted system,
  arrest, fine. Batons, no guns.
- **Missions:** taxi fares, deliveries, street races, with checkpoints
  and money.
- **Transit you can ride:** stations at real spacing (1.6 km rail,
  1.15 km Metro, 900 m monorail), 16-second dwells, board and alight.
- **Save:** money, jobs done, races won, distance, position and
  time of day persist.
- **HUD:** radar with street layout and blips, full-screen vector map,
  clock, prompts, five touch buttons.
- **Audio:** engine, tyres, footsteps, rain, sirens — all synthesised at
  runtime.
- Visible "© OpenStreetMap contributors" attribution.

### 6. Build and delivery

GitHub Actions: ingest → compile → verify (145 gates) → core test suite
→ `assembleRelease` → emulator run that asserts the world loads, all
five shaders link, 30 frames render, audio opens and the land mask
uploads → GitHub Release. Offline-first: no login, no ads, no network,
no backend.

---

## Not built

### Cannot be done from here

1. **No real-hardware frame rate or thermal data.** The only GPU this
   has ever run on is SwiftShader in an emulator. Every performance
   claim in this project is therefore unmeasured.
2. **Touch controls have never been touched by a human.** They are
   driven by unit tests.
3. **Debug signing key.** A release keystore must be generated and held
   in Actions secrets. The private key must never enter the repository.

### Content gaps

4. **No interiors.** Every building is a sealed exterior.
5. **85.7 % of building heights are inferred** from footprint and
   context; 14.3 % carry an OSM tag. No better free source exists for
   Mumbai — Copernicus GLO-30 was tried and cannot resolve dense urban
   morphology.
6. **No station structures.** Transit stops are points on the
   alignment; the 380 OSM platform ways are not modelled, so there is
   no platform to stand on and no shelter, signage or footbridge.
7. **No timetable.** Services run a fixed dwell and a fixed spacing;
   there are no published calling patterns, no line names on the map
   and no departure boards.
8. **No music and no voices.** Everything audible is synthesised.
9. **Upper Thane and Vasai Creek are land,** because OSM maps them as
   intertidal mudflat. Not navigable in this build.
10. **Rooftop helipads are rejected** by the spawn validator, so none
    exist despite Mumbai having many.

### Simulation gaps

11. **Collision is footprint-ring extruded.** Balconies, archways and
    overhangs are solid; you cannot walk under a building that you
    should be able to walk under.
12. **Traffic collides with traffic, pedestrians do not.** Cars queue
    behind each other and bounce off each other with an oriented-box
    test and a mass-weighted impulse. Pedestrians still walk through
    one another and are not pushed out of walls.
13. **No ragdoll.** A knocked-down pedestrian sinks into the pavement
    instead of falling properly.
14. **Some OSM footprints are self-intersecting bow-ties** and mesh
    accordingly.
15. **Vehicle damage is a single number.** It costs engine torque and
    survives getting out and back in, but there is no deformation, no
    smoke, no part loss and no effect on steering or brakes.

### Rendering gaps

16. **Street lights are scenery.** Columns stand along every arterial
    and their heads burn after dark, but nothing collides with them -
    you drive straight through - and the renderer has no dynamic
    lights, so a lamp throws nothing onto the road beneath it.
17. **Buildings cast no shadows.** There are blob shadows under moving
    bodies and nothing else — no shadow map, no dynamic lights at
    night beyond emissive windows.
18. **The sea shader uses `discard`,** which costs early-Z on
    tile-based mobile GPUs. Worth replacing with a stencil or a
    geometry cut if a real device shows it mattering.

### Game gaps

19. **Three mission types only** — taxi, delivery, race. No mission
    chain, no story, no characters, no named contacts.
20. **No lane discipline at junctions.** Traffic queues behind the car
    in front and will not drive through it, but there are no signals,
    no give-way rules and no turn priority: at a crossroads the cars
    that arrive first simply get there first.
21. **No pause or settings screen.** Quality cycles through one button;
    nothing else is configurable in-app.
22. **No in-game map markers you can set,** and no waypoint routing.

---

## Suggested order

The three that would change the game most, in order:

1. **Put it on a real phone.** Every performance number here is
   unmeasured, and items 1–2 cannot be closed any other way.
2. **A mission chain** (item 19) — the systems for money, wanted level,
   checkpoints and delivery already exist; what is missing is a reason
   to use them in sequence.
3. **Junction priority** (item 20) — traffic now queues and crashes,
   which is most of the way to a living road; what is missing is any
   rule about who goes first.
