# Status — what is built, what is not

**Last release that passed device verification: v2.0.50** (run
`38882611667`). Everything since builds and passes 544 core + 28
Android tests, but **fails the on-device launch test** — see
"The device test is failing" below. v2.0.60 is the newest build.
Java across 49 core classes and 11 Android classes,
57 test files holding **543 core tests + 28 Android tests**, and **145
world verification gates**. All green, against the same world CI builds.

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
| **Boat** | 6–12 buoyancy points, analytic waves shared with the renderer, tide, planing, beaching. Four hulls — launch, ferry, lighter, speedboat — picked deterministically from the mooring |
| **Helicopter** | Collective/cyclic, tail rotor torque, translational lift, ground effect, autorotation. The collective is rate-commanded and springs back to a hover, so letting go of the stick holds the height |
| **Aeroplane** | q = ½ρv², Cl/Cd curves, stall, flaps, gear, real runway lengths, no hidden impulse |

Fixed 60 Hz accumulator, max 4 substeps, dt clamped to 0.1 s, render
interpolation. SI units throughout.

Measured: sedan 198 km/h, 0–60 in 6.0 s, 100–0 in 44 m at 0.90 g;
kaali-peeli 135/6.8/13.2; autorickshaw 65; double-decker 74; scooter
85 km/h at 0.62 g; autorotation 11.1 → 7.3 m/s; takeoff roll 894 m
clean, 591 m at flap 25°; aeroplane level cruise at throttle 0.59,
269 km/h, 0.00 m/s climb, with a 36 s phugoid that decays from
11.7 m/s peak to 0.9 m/s in twelve minutes; helicopter cruise 202 km/h, full pedal
58 °/s, hands-off hover holds within 2.5 m/s; harbour launch 28 kn on
the plane against the ferry's 13 kn.

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
- **Pedestrians, blob shadows, moored craft and the aircraft you are
  flying are drawn** — for the first time. Four upload paths converted
  world coordinates to render space with the camera instead of the
  floating origin, so all four were placed tens of kilometres away and
  culled or clipped. You could not see the helicopter you were in.
- **Transit you can ride:** stations at real spacing (1.6 km rail,
  1.15 km Metro, 900 m monorail), 16-second dwells, board and alight.
  All 821 stations are boardable from the street outside them;
  elevated ones have a stair flight, bored ones a street entrance.
- **382 km of elevated guideway is solid** — 14,178 viaduct piers in
  the collision grid, from the same function that draws them.
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

## The device test is failing (v2.0.51 onward)

The app starts, links all five shader programs, loads the world,
opens audio, builds the land mask and renders. Frame one draws
shadows, traffic and craft and survives. Frame two draws the same
groups and about half a second later the **emulator process** is
gone — not the app: no Java exception, no crash log, "no
devices/emulators found". SwiftShader runs inside the emulator, so a
fault there takes the device with it.

Ruled out by experiment, each one a separate CI run:

| Hypothesis | Test | Result |
|---|---|---|
| Host out of memory | `free -m` either side of boot | 16 GB total, 12 GB free. No. |
| Guest RAM too large | 4096 MB → 2560 MB | No change |
| The transit draw | removed it entirely | Still dies, `craft0` last instead |
| Instance count overrun | every draw clamped to buffer capacity | No change; nothing was ever over |
| Non-finite carriage data | 144,540 pose floats checked | 0 bad, heights −6.6…+9.4 m |
| Resident chunk budget | 420 → 280 → 160 | No change |
| Upload volume | added a 768 KB/frame byte budget | No change |

What is established: it is not any particular draw — frames one and
two draw the same groups and only the second is fatal — and it is not
memory. It is something in the second frame's work that SwiftShader
does not survive. A real GPU driver may well be fine with it; that is
*not* a claim that it is, only that this has been reproduced solely
on software rasterisation.

The newly-exercised code is the four upload paths fixed in v2.0.51.
Pedestrians and moored craft had culled to zero for the whole project
because their distance test used the wrong origin, so those VAOs had
never issued a real draw until now.

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
6. **Stations are a platform and nothing else.** Every surface and
   elevated station now has a platform either side at the height its
   line runs at, and standing on one tells you the line and the next
   departure. There is still no shelter, no signage, no footbridge and
   no concourse, and the 380 OSM platform ways are not used - the
   platforms are derived from the alignment.
   Every station now has a way in: elevated platforms get a stair
   flight down to the pavement and bored stations get a street
   entrance over the tunnel. Walking up the stairs is not simulated —
   standing on the pavement outside a station is enough to board,
   the same abstraction every interior in the game uses.
7. **The timetable is a headway, not a calling pattern.** Each line
   departs on a fixed interval from a fixed phase, so a board is
   always right, but there are no real timings, no fast/slow services
   and no destinations beyond the two terminals the line is named
   after.
8. **Trains, Metro and monorail are not drawn.** They run, call at
   every station and can be boarded; the carriages are simply not
   rendered. Isolated on a device: the frame that first draws a rake
   is the last frame the emulator survives, with no Java exception and
   no crash log, which is what a fault inside SwiftShader looks like.
   Controlled against the other candidate — halving the resident chunk
   budget moved nothing. Not an instance overrun (9 against a buffer of
   160), not non-finite data (144,540 pose floats checked, 0 bad), not
   upload volume. Cause not yet found, so the draw is off rather than
   shipping a build that kills the driver two frames in.
9. **5.1 % of at-grade track is buried in the terrain.** 78 km of the
   1,519 km that runs at grade sits more than 0.3 m inside the drawn
   ground, worst case 1.97 m; the median clearance is +0.86 m. The
   compiled formation is a morphological opening of the DEM, so a
   narrow rise the opening removed is still drawn, and the track goes
   through it. Fixing it properly means either carving cuttings into
   the terrain heightfield or putting the line on stilts wherever the
   surface model has a spike. Measured, not fixed.
10. **No music and no voices.** Everything audible is synthesised.
11. **Upper Thane and Vasai Creek are land,** because OSM maps them as
   intertidal mudflat. Not navigable in this build.
12. **Rooftop helipads are rejected** by the spawn validator, so none
    exist despite Mumbai having many.

### Simulation gaps

12. **Collision is footprint-ring extruded.** Balconies, archways and
    overhangs are solid; you cannot walk under a building that you
    should be able to walk under.
13. **Traffic collides with traffic, pedestrians do not.** Cars queue
    behind each other and bounce off each other with an oriented-box
    test and a mass-weighted impulse. Pedestrians still walk through
    one another and are not pushed out of walls.
14. **A knockdown is not a ragdoll.** A body topples about its feet,
    slides and gets up, but there are no joints and no limb physics:
    everyone falls the same way, and they fall through each other.
15. **Some OSM footprints are self-intersecting bow-ties** and mesh
    accordingly.
16. **Vehicle damage is a single number.** It costs engine torque and
    survives getting out and back in, but there is no deformation, no
    smoke, no part loss and no effect on steering or brakes.

### Rendering gaps

16. **Street furniture is scenery.** Columns and kerbed pavements run
    along the arterials and the lamp heads burn after dark, but
    nothing collides with any of it - you drive through a lamp post
    and over a kerb without noticing - and the renderer has no dynamic
    lights, so a lamp throws nothing onto the road beneath it.
17. **Buildings cast no shadows.** There are blob shadows under moving
    bodies and nothing else — no shadow map, no dynamic lights at
    night beyond emissive windows.
18. **The sea shader uses `discard`,** which costs early-Z on
    tile-based mobile GPUs. Worth replacing with a stencil or a
    geometry cut if a real device shows it mattering.

### Game gaps

19. **A contract ladder, not a story.** Seven chapters of escalating
    work that force the player through every kind of vehicle, with
    bonuses and named contacts. There is no narrative, no dialogue,
    no cutscenes and no characters - the contacts are roles, not
    people.
20. **Signals, but no give-way.** 14,921 junctions have working two-
    phase lights that the traffic obeys. The other 28,592 junctions
    are uncontrolled and have no priority rule at all, and nothing
    anywhere indicates or respects a turn.
21. **No pause or settings screen.** Quality cycles through one button;
    nothing else is configurable in-app.
22. **No in-game map markers you can set,** and no waypoint routing.

---

## Suggested order

The three that would change the game most, in order:

1. **Put it on a real phone.** Every performance number here is
   unmeasured, and items 1–2 cannot be closed any other way.
2. **Give-way at the unsignalled junctions** (item 20) — three in four
   junctions still have no priority rule.
3. **Make the street furniture solid** (item 16) — lamp posts, kerbs
   and now signal masts and platforms are all scenery you drive
   through.
