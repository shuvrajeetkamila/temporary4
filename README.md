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

## The game layer

Walk up to any vehicle, press **ENTER**, drive off. Stop, press **EXIT**, and
it is still standing there when you turn round. Taking a car that is already
moving inherits its speed rather than stopping it dead.

Ten unbranded vehicles, weighted by the real mix of the city's traffic, so
scooters and autorickshaws dominate and buses are a steady minority:

| | |
|---|---|
| Kaali-peeli | 1,180 kg, upright, black under yellow |
| Autorickshaw | 340 kg on three wheels — tips into corners, and not licensed south of Mahim |
| Double-decker | 12.4 t, 10.2 m, understeers because it weighs 12.4 tonnes |
| Single-deck bus, tempo, scooter | |
| Hatchback, saloon, SUV | private traffic |
| Police interceptor | not ambient; spawned by the wanted system |

Each is described to the solver by mass, wheelbase, inertia tensor and
centre-of-mass height — not by a handling fudge applied afterwards.

A full day runs in 24 minutes, keyed to Mumbai's own light: washed-out hazy
noon, the gold at half past five, the sunset the city goes outside to watch,
and a 25-minute tropical dusk rather than a European hour and a half. After
dark the windows come on — only the windows, because the emissive mask lives
in the texture's alpha rather than being a per-face constant.

Every surface is textured, and there is not one image file in the repository:
the atlas is 15 procedurally generated tiles built at startup in about 300 ms.

Offline. No login, no ads, no network permission, no backend.

---

### Making a living

Work is offered by the vehicle you are in, so picking one is a decision
about what you are going to do rather than a change of paint:

| Vehicle | Work | Pay | Clock |
|---|---|---|---|
| Kaali-peeli taxi | Fares | Rs 28 + Rs 18/km, up to a third more for a quick run | none |
| Tempo, auto | Deliveries | Rs 60 + Rs 24/km | yes, and it pays nothing late |
| Hatchback, sedan, SUV | Street races | Rs 240 + distance | yes, that is the race |

Buses, scooters and police cars are not for work.

Pickups and destinations are real named places out of OpenStreetMap, and
every stop is snapped onto the road graph before it is used - a
destination taken straight from the place table lands inside a building
about as often as not, and a checkpoint you cannot drive into is a
mission that can only be failed. Races are laid out as a ring of gates
around wherever you are, each one snapped to tarmac, finishing back at
the start.

The current stop shows as a glowing cylinder you drive into, plus a blip
on the radar that clamps to the rim when the target is off the map. Fares
are untimed on purpose: being sacked because of Mumbai traffic is not
fun, so the clock only ever adds to the fare. Deliveries and races are
timed, and the clock turns red in the last fifteen seconds. Getting out
of the vehicle cancels the job.

### The law

Crimes are running somebody over, ramming a vehicle, hitting a police car
and swinging the baton - all of them only count above 4 m/s, so a crowded
pavement and a nudge in traffic do not make you wanted. Heat is
continuous and the stars are a view of it, so a run of small offences
escalates the same way one big one does.

Heat only falls once no police unit has been within 95 m for eight
seconds: losing them is the mechanic, waiting is not. One car is sent per
star and they drive progressively further over the limit. They do not
give way to you, and they do not despawn with distance the way traffic
does - they are stood down when you lose them.

Stop within 7 m of a police car for two and a half seconds and you are
nicked: a quarter of your money, the wanted level cleared, the current
job dropped. Keep moving and the arrest timer resets.

**There are no guns in this game.** The only force available on foot is a
baton, with a 2.6 m reach and a forward arc, which knocks people down and
draws attention.

### The monsoon

Rain and ground wetness are deliberately two different numbers. Rain is
what is falling now and drives the overlay and the sky; wetness is how
wet the tarmac is, and it drives grip and the specular sheen. Tarmac
soaks in about 36 seconds and takes five minutes to dry, so the road
stays slick and shining long after a squall has gone through - which is
most of what a Mumbai monsoon looks like, and means the handling change
outlasts the downpour that caused it.

Squalls come from value noise over the clock rather than a random walk,
so the weather is a pure function of time: the same moment always gives
the same sky and nothing drifts if frames are dropped. The season knob
raises a threshold rather than scaling the intensity, so a dry month is
genuinely dry instead of a drizzle that never stops.

The rain itself is a full-screen procedural pass, three slanted layers at
different fall speeds, not particles - rain that fills the frame needs
tens of thousands of quads and none of them survive a tile-based mobile
GPU.

## Verification

Nothing is published unless all of this passes first:

- **123** world gates in `tools/verify_world.py`, including 51 geographic
  checkpoints, run against the compiled files with an independently derived
  projection.
- **111** core tests in `core/src/test`, including the same 51 checkpoints
  read back through the game's own loader.
- The built APK is then inspected rather than trusted: badging, signature,
  and a check that all four world files really are inside the zip.
- An emulator job installs the APK on Android 11, launches it, and fails
  unless logcat shows every shader linked, the world loaded to its full
  52,540 × 72,540 m extent, and thirty frames were rendered with no fatal
  exception. The log is kept at `data/reports/device_test.log`.

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
