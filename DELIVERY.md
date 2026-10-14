# Delivery notes

What this build is, what it is not, and what has actually been measured.

---

## What was verified, and by what

| Gate | Count | Where it runs |
|---|---:|---|
| Geographic checkpoints (Python, independent projection) | 51 | `tools/verify_world.py`, before the APK is built |
| Total world gates (files, provenance, extent, projection, chunks, coverage, network, places, heights) | 123 | same |
| Geographic checkpoints (Java, through the game's own loader) | 51 | `core/src/test/.../CoverageTest` |
| Core tests overall | 111 | `gradle :core:test` |

The Python verifier re-derives the ENU projection from the WGS84 constants
rather than importing `aoi.py`, so a bug in the projection cannot validate
itself. The Java suite then re-checks the same 51 places through the loader
the game actually uses, so a correct file read by a broken reader still
fails. The Java checkpoint list is **generated** from `tools/aoi.py`; it was
hand-copied once, two latitudes drifted, and the two gates disagreed.

`mumbai.requireWorld=true` is set in CI. Without it a missing world would show
as forty quiet skips; with it, it is a hard failure.

---

## What the APK was observed to do

Every build installs onto an Android 11 (API 30, x86_64) emulator and is
launched, and the job fails unless the log shows all of this. From the run
that produced this release:

```
Performing Streamed Install
Success
Status: ok   LaunchState: COLD   TotalTime: 3460
MumbaiGL: GL vendor Google renderer Android Emulator OpenGL ES Translator
          (Google SwiftShader) version OpenGL ES 3.0
MumbaiGL: GL ready, programs linked
Mumbai  : world loaded in 6172 ms: 3869 chunks, 1283 places
Mumbai  : MUMBAI_WORLD_READY chunks=3869 places=1283 extentX=52540 extentZ=72540
MumbaiGL: 1 decode workers started
MumbaiGL: atlas: 15 layers of 128x128 in 306 ms
Mumbai  : MUMBAI_WORLD_READY chunks=3869 places=1283 extentX=52540 extentZ=72540
Mumbai  : MUMBAI_RENDER_OK frames=30 quality=LOW resident=58 draws=29
```

So: it installs, it starts cold in 3.5 s, every shader compiles and links on a
real GLES 3.0 driver, the packaged world parses to its full 52,540 × 72,540 m
extent, the streamer brings 58 chunks resident, and thirty frames are drawn
with no fatal exception and the process still alive. The committed log is at
`data/reports/device_test.log`.

## What has *not* been measured

This matters more than anything above.

- **No frame rate or thermal behaviour has been measured on real hardware.**
  No physical Android device was available at any point. The thermal tiers are
  implemented and unit-tested for the property that matters — that eco mode
  reduces resolution, streaming radii, sea tessellation, upload budget and
  agent counts, and not merely the frame cap — but **no claim is made about
  what frame rate any particular phone will produce.**
- The only GPU this has ever run on is SwiftShader, a software rasteriser
  which renders single-digit frame rates by nature. The run above proves the
  code path works end to end. It says **nothing** about performance, and the
  `quality=LOW` in that log is the renderer correctly recognising a slow
  device, not a measurement of a real one.
- Battery drain, sustained thermal throttling and memory pressure over a long
  session are all unmeasured.
- Touch controls have not been exercised by a human. They are driven by unit
  tests and were not play-tested, because there was nothing to play them on.

---

## Known limits

**Content**

- **85.7 % of building heights are inferred**, not surveyed. 14.3 % carry an
  OSM `height` or `building:levels` tag; the rest come from a neighbourhood
  median blended with a class default. The reasoning and the exact split are
  in `DATA_SOURCES.md`.
- **The upper reaches of Thane Creek and Vasai Creek are modelled as land.**
  OSM maps them as intertidal mangrove and mudflat, which is what they are,
  so they are not navigable water in this build.
- Navi Mumbai, east of Thane Creek, is distant low-detail context, as the
  brief allows.
- **Audio is entirely synthesised** - every sound is computed sample by
  sample at runtime and there is not one recording in the package. That
  keeps it original, but it also means it sounds synthesised: engines,
  sirens, horns, rain, wind, collisions and footsteps are there, and
  music and voices are not. Footsteps are driven by the speed the player
  actually makes over the ground rather than by the stick, so being
  shoved against a wall at full input stops the sound as well as the
  movement, and they brighten and ring longer in standing water.
- **Boats, helicopters and aeroplanes are now things you can walk up to
  and get into.** Until this build all three flight and seakeeping models
  existed, were tested, and were unreachable: the only way into one was a
  debug button that swapped your body in place. The compiler now derives
  89 moorings from the map - 56 boats at piers and ferry wharves, 27
  helicopters on marked pads, 6 aeroplanes on the runways at Chhatrapati
  Shivaji Maharaj and Juhu - and they are drawn, blipped on the radar,
  marked on the full-screen map, and boarded with the same ENTER you use
  on a car. Every one of the 89 is covered by a test that walks up to it
  and gets in.
- **The city is solid now**: buildings, bridge decks, other vehicles and
  deep water all push back. What is *not* modelled: no interiors, so
  every building is a sealed exterior; collision is against the footprint
  ring extruded to the roof, so a balcony or an archway is as solid as
  the wall it is on; pedestrians are not pushed out of walls, only the
  player is; and traffic does not collide with traffic, only with you.
- **Pedestrians are drawn** now, in four outfits. They do not animate:
  there is no walk cycle, so a person slides along facing the way they
  are going, and one who has been knocked down sinks into the pavement
  rather than falling over.
- **Shadows are blobs**, one dark ellipse on the ground under each body,
  faded by sun elevation and by night. Buildings cast none. There is no
  shadow map and no dynamic light anywhere in the renderer.
- **Progress is saved** to SharedPreferences on pause and on every
  completed job: money, jobs by kind, best race time, the clock and
  where you were standing. There is one slot and no manual save.
- **Traffic density is a budget spent near the player**, not a simulation
  of the whole city. Cars and pedestrians are populated within 300 m and
  given up past 620 m; beyond that the streets are empty, because you
  cannot see them. About two fifths of the cars are parked at the kerb.
- Paid work (fares, deliveries, street races), money, checkpoints,
  reactive traffic, fleeing pedestrians, the radar, the full-screen
  map with tap-to-teleport, the wanted level,
  police pursuit, the baton, the monsoon, and trains, metro, monorail and
  harbour vessels are all in. Police cars sound like police cars and
  their light bars now flash: blue and red in antiphase, two and a half
  times a second, unshaded and untextured because a lamp is a light
  source rather than a surface, and dark unless somebody is actually
  responding to you. The bars do not cast light on anything around them;
  there are no dynamic lights in this renderer.
  **Officers on foot** now turn out alongside the patrol cars, from one
  star, and more of them when you are on foot than when you are driving.
  They run rather than walk, close to arm's length and stop, give up at
  230 m so a chase has an end, and get back up after being batoned -
  being able to delete the police force with a stick is not a chase. The
  arrest is the same proximity test a patrol car uses, so being caught
  means the same thing on foot as in a car. They wear khaki with a cap
  and a baton on the belt: a colour and a silhouette, no crest, no
  insignia and no lettering.
- **Pedestrians walk.** Until this build they slid along the pavement
  fully rigid. Each limb is tagged in the mesh and swung about its own
  joint in the vertex shader - arms against legs, a small bob on the
  whole body at twice the rate, stride scaled by how fast the person is
  actually going and zero when they stop, so somebody waiting at a corner
  stands still instead of marching on the spot. There is no skeleton and
  no animation data; it is trigonometry on tagged vertices. Knocked-down
  pedestrians are still sunk into the pavement rather than ragdolled.
- **Two rendering faults reported from play are fixed, and both were
  real.** First, a tile that crossed a detail-level boundary stopped
  being drawn for the whole round trip through the I/O queue, a worker
  thread and the upload budget - and because the sea is drawn under
  everything, a missing tile is not a gap in the ground, it is water.
  Driving anywhere crossed those boundaries constantly. The tile now
  keeps its old mesh on screen until the new one is ready, the mesh it
  replaces is handed back to be freed instead of being silently
  overwritten (that leaked a tile's worth of GPU memory per detail
  change, and a driver with no memory left stops honouring uploads,
  which is what torn and striped geometry is), and a cancelled rebuild
  no longer strands a tile in a state where it is neither drawable nor
  reclaimable.
  Second, the terrain is meshed at 32, 16 or 8 subdivisions a chunk
  depending on distance, so two neighbouring chunks at different detail
  levels did not agree about the ground along the edge between them: one
  follows it every 32 m, the other cuts straight across 128 m. Measured
  across this world that tear reaches **137 m** on the steep ground at
  Malabar Hill and the Powai slopes, 15 m at the 99.9th percentile, and
  a few metres almost everywhere else - a straight line of sea or sky
  drawn across the land. Each chunk now hangs a skirt from its edges
  down past its own lowest ground, which needs no agreement with
  neighbours that are streamed independently and change detail level
  constantly. It costs about 256 triangles on a 2048-triangle chunk and
  is invisible unless it is doing its job.
  Third, the tide is a real one - just under two metres either way,
  which is what Mumbai Harbour does - and **6.3 % of the land in the
  20 m terrain model sits below that line**: the reclaimed ground along
  Back Bay, the dock aprons, the low ground behind Mahim. At high water
  the sea was drawn straight over those streets, and where the two
  surfaces passed within centimetres of each other they fought for the
  depth buffer and striped the road. The land mask has always been the
  single authority for where the coast is; the sea shader now samples
  the same mask and discards any pixel that falls on land. The mask
  transform is rebased with the floating origin every frame, because a
  texture coordinate built from a world constant and a render-space
  position would have come apart only after a long drive.
- **Transit has no stations or timetable.** Services run between the ends
  of their line with a dwell at each terminus; they do not stop at
  platforms, you cannot board one, and the 380 OSM platform ways are not
  modelled. The metro is drawn 12 m below the street with no tunnel
  around it, so from underneath there would be nothing to see.
- The control mapping and all five locomotion modes are covered by
  tests that assert direction, not just magnitude. Nine faults found and
  fixed in that audit are listed in the README.
- **Boats cannot be left anywhere except near the shore.** There is no
  walkable pier deck over open water, so a mooring two hundred metres off
  the beach would be a boat you could never get out of. The compiler
  therefore walks every mooring in towards the nearest land until it is
  within thirty metres of somewhere to stand - 79 of them were moved -
  and drops the 53 it could not bring alongside. Out at sea the game
  refuses the request and says so rather than drowning you.
- **Some OSM footprints are self-intersecting** after simplification -
  the first building this project's collision tests picked had ten
  vertices that crossed each other twice. They are still extruded and
  still collide wall by wall, which is correct, but "inside the
  building" is not a well-defined question for one of them.
- **Rooftop helipads are still rejected**, for the same reason: there is
  no way up onto a roof. The 27 helicopters are on ground-level pads.
- **The scooter's lean is kinematic.** The rider is modelled as a control
  loop that commands the lean the corner asks for, at
  `tan(lean) = a_lat / g`, with a feed-forward term for the overturning
  moment. She is not modelled as a mass that countersteers, and the
  machine will not low-side: its grip is capped at `tan(40 deg)`, which
  is the most lean the rider will use.
- Being arrested costs a quarter of your money and clears the wanted
  level. There is no hospital or police-station cycle, because that needs
  interiors and a save system and neither exists here.
- There is **no shadow pass**. Lighting is a sun term plus hemispheric
  ambient with no occlusion.
- Interiors do not exist; buildings are solid extrusions.

**Simulation**

- **Ambient traffic and pedestrians are kinematic**, not rigid bodies. They
  follow the real OSM road graph, keep left as Indian traffic does, and are
  placed on genuine centrelines — but they are not simulated with the vehicle
  model the player's car uses. Running 140 full vehicle models on a phone
  alongside the player's own is not affordable, and claiming otherwise would
  be the dishonest option.
- Collision is the terrain height field plus per-building bounding boxes. It
  is not a mesh-accurate collision world.
- The five player vehicles are each force-and-torque based with no transform
  or velocity assignment anywhere, with one stated exception: the human
  controller applies a **bounded** force toward a desired velocity, because a
  walking person is a self-propelled system whose ground reactions cannot be
  predicted externally. The bound is 1,750 N, so walls still stop you and a
  car still knocks you over.

**Build**

- The APK is **debug-signed**. Android will warn about an unknown source on
  install. A release key is deliberately not committed: shipping a private
  signing key in a public repository would be worse than shipping an
  unsigned build.
- The APK is universal — one file, all ABIs — because there is no native code
  to split.

---

## Physics: what each model does, and what it refuses to do

Each of these was wrong at least once, and the test that caught it is named.

**Car.** Per-wheel spring-damper suspension, a magic-formula tyre with a
friction ellipse, a torque curve, a six-speed box with a slipping clutch, and
left-hand traffic. Wheel spin uses driveline inertia reflected through the
square of the gear ratio: against roughly 700 N·m the bare 1.2 kg·m² of a
wheel diverges at 60 Hz. Grip is `(unpaved ? 0.62 : 1.0) × (1 − 0.32·wetness)`,
and `CarPhysicsTest.wetGrip` asserts the wet stopping distance really is
longer. 0–100 km/h takes about 12.7 s; braking from 100 takes 20–85 m.

**Boat.** Eight buoyancy sample points against the same analytic wave sum the
vertex shader displaces the sea with, so a boat cannot float above the water
it is drawn in. Righting is `−sin(heel)·m·g·GM`, not `−heel·k`: a linear term
cannot let a vessel stay capsized, and `rightsItself` checks recovery from a
50° knockdown. Planing lift is capped at 0.75 g and always charged 0.26×
induced drag, because planing without a drag cost is a free-energy pump —
`planingIsNotFree` asserts the boat reaches a terminal speed. Grounding is
friction against the supported weight, so a ferry under full power cannot
drive up a beach.

**Helicopter.** Blade-element momentum theory with an **induced-inflow fixed
point**, iterated twenty times with damping. This is the whole model. Compute
the thrust coefficient from collective and climb rate alone, without letting
the air the rotor is already pulling down reduce the angle of attack, and
hover thrust comes out about three and a half times too high: the machine
leaps off the pad at 2 g on half collective. `hoverThrustIsNotOverestimated`
pins the hover collective to 5–12.5°, where a real machine sits. Ground
effect, translational lift, tail-rotor torque and autorotation all follow from
the same solve.

**Aeroplane.** `q = ½ρv²`, a lift curve that genuinely stalls, and a drag
polar whose induced term grows as the square of lift. There is no hidden
impulse at rotation: `needsRunwayLength` asserts the takeoff roll exceeds
150 m, and it comes out near 900 m. Three undercarriage contact points, not
one — a single contact with a levelling torque cannot be rotated, because the
levelling term scales with the normal load and swamps the elevator. Control
authority is balanced against the stability derivatives so full aft stick
trims to about 18°, just past the stall, rather than 32°.

**Human.** Capsule controller with walking, running, sprinting with stamina,
jumping, swimming and ragdoll. Ragdoll is the same body with the locomotion
force switched off, so there is no discontinuity when it triggers.

---

## Architecture notes worth knowing

**Scale.** True 1:1 metres throughout. A local ENU projection with the WGS84
meridian arc expanded to e⁶ and a four-step Newton inverse; no haversine
anywhere, because mean-Earth-radius haversine is 0.45 % wrong at 19° N. The
projection round-trips to within 0.000 mm and agrees with Vincenty to better
than 0.1 %.

**Floating origin.** Everything authoritative is `double`. The render origin
rebases to a 512 m grid whenever the player drifts past 1,024 m, so no
coordinate handed to the GPU exceeds about 2.2 km. The test asserts both the
need and the fix: a 32-bit float resolves to about 4 mm at 45 km, and after
rebasing the worst resolution anywhere in a full diagonal traverse is under
0.5 mm.

**Fixed step.** 60 Hz, at most four substeps per frame, any frame longer than
100 ms clamped. The leftover fraction drives render interpolation with a
shortest-arc slerp, so a 30 Hz display shows smooth 60 Hz motion. Teleports
snapshot twice, or the first frame interpolates from wherever the player used
to be and the camera flies across the city.

**Streaming.** Nine states from `UNLOADED` through `ACTIVE_HIGH` to
`EVICTING`. Chunks are deflated individually inside the container and read as
byte slices, so a worker can inflate one 30 KB tile without touching the other
fourteen megabytes. Decode happens on background threads, GPU upload is capped
per frame, and the preload ring leads the player by `min(2600, speed × 9)`
metres scaled by altitude.

---

## Reproducing this build

```bash
python tools/aoi.py                     # 51 checkpoints, extent, projection
python tools/extract_osm.py --pbf data/pinned/mumbai-aoi.osm.pbf --out data/layers
python tools/compile_world.py --layers data/layers --dem data/dem \
                              --out android/src/main/assets/world
python tools/verify_world.py --world android/src/main/assets/world   # 123/123
gradle :core:test -Dmumbai.requireWorld=true                         # 111 tests
gradle :android:assembleRelease
```
