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

### Trains, the metro and the harbour

Suburban rail, the metro, the monorail and the harbour ferries run on
real OpenStreetMap alignments. They are deliberately **not** in the road
graph - that is filtered to driveable classes, which is what stops
traffic spawning in a subway tunnel and a taxi fare being sent to a point
in the middle of the harbour. Transit gets its own network.

OSM splits railway ways at every junction, signal and bridge: the Mumbai
extract has 2,607 fragments averaging 733 m. A train that terminates
every 733 m is not a train, so the compiler chains fragments of the same
class end to end wherever they share an endpoint. That gives 459 routes
over 1,733 km, the longest 63 km.

| Mode | Routes | Length | Consist | Cruise |
|---|---|---|---|---|
| Suburban rail | 430 | 1,407 km | 9 cars | 80 km/h |
| Metro | 21 | 196 km | 6 cars | 61 km/h |
| Monorail | 3 | 40 km | 4 cars | 49 km/h |
| Harbour vessels | 5 | 91 km | 1 hull | 12 kt |

Carriages are placed by walking back along the route from the nose, so a
train bends through curves instead of being a rigid box - that one detail
is most of what makes rail read as rail at a distance. Services run to
and fro and wait at a terminus rather than looping, because a train that
vanishes off one end and reappears at the other is more jarring than one
that turns round.

The metro is drawn 12 m below the street, the monorail 8.5 m above it,
and vessels sit at sea level whatever the terrain underneath says.

### Sound, from nothing

There are no audio files in this project. Every sound is generated a
sample at a time while you play, for the same reason the artwork is
procedural: it is original, it is unencumbered, and nobody has to ship a
licence with it.

That constraint turns out to be an advantage. The engine note is not a
loop picked to be nearly the right pitch; it is built from the firing
frequency implied by the crankshaft speed the physics is already
solving for. A four-stroke fires twice per revolution per pair of
cylinders, so `rpm / 60 x cylinders / 2` is the note you actually hear.
The consequence is that the sound cannot drift out of step with the
simulation - revs climb through a gear and drop across a shift without
anything being arranged to make that happen - and a rickshaw's single
cylinder sits two octaves under a taxi's four for free.

The waveforms are summed sine partials rather than naive ramps, with the
partial count falling as pitch rises so nothing ever folds back over
Nyquist. A sawtooth computed the obvious way aliases into a kazoo at
high revs; `Synth.partialsFor` is what prevents that.

Around you, the mixer carries twelve positional voices, which is far
fewer than a busy junction has cars, so `AudioDirector` chooses. The
rule is priority first and distance second: a siren outranks a horn, a
horn outranks an engine, and within a class the nearest wins. A police
car 200 m back therefore keeps its voice while thirty-nine closer cars
lose theirs, which is correct - knowing the law is behind you matters
more than a parked tempo idling at your elbow. Sources are panned with a
constant-power law, attenuated by inverse distance rather than inverse
square, and faded to silence at their cull radius so nothing clicks out.
Doppler is real but clamped, because the textbook formula goes to
infinity as closing speed approaches Mach 1 and a physics glitch should
not produce a shriek.

Rain is filtered noise whose level and brightness follow the same
`Weather.rain` the shader uses, slightly decorrelated between the ears
so it sits around you. Wind grows with the square of speed, like drag.
Collisions get a short low thump scaled by impact speed.

The whole mix passes through a soft clipper. Its shape is not arbitrary:
`v - v^3/6.75` has zero gradient exactly where it reaches 1.0, so it
meets the rail tangentially and never overshoots. An earlier divisor
peaked at 1.067, which then clamped in the integer conversion and
produced precisely the hard edge the clipper exists to avoid - a test
caught it.

All of this is pure Java with no Android types, so it is tested by
rendering buffers and measuring them: Goertzel for pitch, RMS for level,
a monotonicity sweep for the clipper, and a four-thousand-buffer run
that fails if the render path allocates, because an audio thread that
makes garbage eventually meets the collector mid-buffer and you hear it.
Only `AudioOutput` touches `AudioTrack`, on its own thread, and every
failure there is swallowed - a phone with a busy audio device should
still be playable in silence.

### The map, and getting across the island

The radar shows 320 m. The city is 52 km by 72 km. Those two numbers do
not go together: the radar tells you which way the next junction bends
and nothing whatever about where Bandra is relative to where you are
standing.

So there is a full-screen map on the MAP button, from every profile
including mid-flight. Drag to pan, pinch or use the buttons to zoom from
a 200 m junction out to the whole island in one screen, tap to drop a
marker, GO to teleport to it. ME recentres on you. The simulation stops
while it is up - reading a map at 90 km/h is not a feature - and because
the engine returns before it touches the fixed clock, no time
accumulates while you are deciding and the step accumulator does not
have to swallow it afterwards.

A tap is pulled onto the nearest road within 140 m when there is one,
because a destination in the middle of a block is somewhere you cannot
drive out of. Water is allowed but the marker turns red unless you are
in something that floats or flies, and GO greys out. Teleporting is not
just a position change: the body lands on the ground at the far end,
velocity and spin go, a car's wheel speeds and clutch are reset rather
than carried across, and the floating origin rebases - which is rather
the point of having one, since a 50 km jump is exactly the case single
precision cannot survive.

Zooming out drops road classes rather than trying to draw all 352,267
graph edges: at city scale only the trunk network is legible anyway. The
world-to-screen transform goes through the same `Minimap.mapX` and
`mapY` the radar uses, because the radar once had two copies of that
rotation that disagreed, and road lines and mission blips drew in
different places.

### Controls, and an audit of them

Every control is mapped in one place, `app/ControlMapping`, which lives
in core rather than in the Android view so it can be tested without a
device. Buttons are addressed by what they do, never by where they sit
on screen, because keying off the slot index is how two of them ended up
sharing a source.

| | left stick | right stick | buttons |
|---|---|---|---|
| On foot | move (push further to run) | look | SPRT, JUMP, ENTER, BATON, CAM, SWAP |
| Car | throttle / brake | steer | HBRK, REV, EXIT, CAM, SWAP |
| Boat | ahead / astern | rudder | EXIT, CAM, SWAP |
| Helicopter | collective + pedals | cyclic | ENG, EXIT, CAM, SWAP |
| Aeroplane | throttle + rudder | elevator + aileron | BRAKE, FLAP+, FLAP-, GEAR, EXIT, CAM, SWAP |

Auditing all five turned up nine faults, none of which any existing test
could have caught, because the physics tests all checked magnitudes and
the input layer had never been tested at all:

- **The car steered the wrong way.** Push the stick right, the car went
  left. Body +X is the vehicle's *port* side in this frame - world X is
  east, Z is south, forward is +Z, and the right of something facing
  south is west. The boat's rudder and the aeroplane's ailerons and
  rudder were inverted for the same reason.
- **The helicopter could not take off.** Hovering 2450 kg on a 5.35 m
  rotor needs about 335 kW, or 8100 N m at 394 rpm. The engine was
  specified at 3400 N m. The governor saturated instantly, the rotor
  drooped below 200 rpm and the machine sank at 3 m/s with the
  collective against the stop.
- **Neutral pedal gave 45% of the anti-torque**, so hands off the pedals
  spun it up. That only looked survivable while the engine was too weak
  to produce torque worth cancelling.
- **`Quat.roll()` returned the opposite of its own documentation**, for
  the same body-axis reason.
- **The sixth on-screen button was off the bottom of the screen** on
  every handset. Buttons sat at fixed offsets of 90 to 390 dp from the
  top, which needs a view 416 dp tall; a phone in landscape is 320 to
  412. They are now laid out from the real view size.
- **RUN and JUMP were the same button**, so setting off at a run always
  launched you into the air. Running now comes from how far the stick is
  pushed.
- **GEAR and the wheel brakes were the same button** in the aeroplane.
- **Flaps could only ever be extended** - nothing set `flapsUp`, so once
  they were out they stayed out.
- **ENTER was drawn as an en dash** in the boat, helicopter and
  aeroplane. The button worked; nothing told you it was there.

### A second pass over the physics

The first audit checked signs and stability: that a torque turns a
vehicle the right way, that nothing diverges. Everything passed. A
double-decker bus still could not exceed 2.5 km/h, and a saloon with the
throttle shut quietly drove itself backwards and kept accelerating.
Neither shows up in a sign test or a magnitude bound.

The second pass drove every vehicle and measured what it does against
what the real thing does. Eight faults:

| Fault | Effect |
| --- | --- |
| `configure()` never sized the running gear | Every vehicle inherited a saloon's 42 kN/m springs, 0.32 m wheels, 2.6 kN m brakes and 320 N m clutch. A 12.4 t bus needs 30 kN per corner against a spring good for 9.2 kN: it bottomed out and sank to y = -21 m. A 142 kg scooter was thrown into the air by its own suspension. |
| Slipping clutch against a bare wheel inertia | Full capacity through a 29:1 first gear is 15 rad/s in one 60 Hz step. |
| Explicit tyre reaction | Drive force flipped sign every few steps; the bus alternated +25 kN and -31 kN and averaged nothing. |
| Overrun torque applied in a fixed direction | Every car idled itself into reverse; the saloon reached 4.1 m/s backwards. |
| Torque curve and shift points in absolute rpm | A 2300 rpm diesel bus never reached its own torque plateau; a scooter's engine braking was six times its peak torque. |
| One shared final drive | A rickshaw was geared for 95 km/h and a bus for nearly 100. |
| Rotor torque omitted climb power | The helicopter climbed at 148 m/s - about 7 MW against gravity - on a 560 kW engine. |
| Blade element used induced inflow, not total | Invisible in a hover, where the two are identical. In a descent thrust collapsed to nothing, so an engine failure was a 105 m/s free fall rather than an autorotation. |

Spring rates now come from a target ride frequency, damping from a
fraction of critical, brakes from a 1 g lock, clutch from 1.6x peak
torque, final drive from the speed the vehicle is meant to reach. The
clutch is clamped to its synchronisation torque and the tyre reaction
takes one Newton step of backward Euler, which is a no-op whenever the
wheel is well conditioned and only bites when it is genuinely stiff.

Measured afterwards, against the real figures:

| | before | after | real |
| --- | --- | --- | --- |
| Double-decker top speed | 2.5 km/h | 74 km/h | ~70 |
| Autorickshaw top speed | 14.6 km/h | 68 km/h | ~60 |
| Saloon 0-100 km/h | 17.6 s | 12.9 s | ~10 |
| Braking 100-0 | 44 m | 44 m | ~40 |
| Lateral grip, car | 0.15 g | 0.82 g | 0.8-0.9 |
| Lateral grip, bus | 0.00 g | 0.54 g | lower than a car |
| Turning circle, bus | n/a | 10.3 m | ~11 |
| Turning circle, rickshaw | n/a | 2.7 m | ~2.5 |
| Hover collective | 1% (broken) | 58% | 50-60 |
| Helicopter climb | 148 m/s | 12.6 m/s | ~10 |
| Standing jump | 0.94 m | 0.62 m | 0.4-0.5 |

Seventeen regression tests hold all of it, including the ones that would
have caught the original faults: that the suspension can carry the
vehicle's own weight, that nothing moves with the throttle shut, that
drive force does not change sign during steady acceleration, and that
the helicopter cannot climb on energy its engine does not have.

Autorotation is survivable and has a best-glide collective, but the
descent is about twice as steep as a real one.

### A third pass: the world as you actually meet it

The second pass checked the models in isolation. This one walked around
the city and looked at it, which found four things no unit test was
asking about.

**Every vehicle in the game was drawn facing the wrong way.** The frame
has X east, Z south and a heading measured clockwise from north, so a
body's nose at local +Z points *south* when it is not rotated at all.
`Quat.yaw` fed a compass heading straight into a rotation about world up,
and the instanced vertex shader did the same, so both produced a body
mirrored about the east–west axis: a taxi driving north was drawn facing
south, and only something travelling due east or due west came out right.
Everything that turns a heading into an orientation went through those
two places, so everything inherited it — the traffic, the trains, the
metro, the ferries, the car you got into, and the heading you kept when
you teleported. One sign in each.

**The low-rise city was being deleted rather than simplified.** Levels of
detail dropped any building under 220 m² or 9 m at medium range and under
2600 m² or 34 m at long range, which is most of Mumbai. From a kilometre
up, Dharavi, Govandi and the suburbs were bare ground with a few towers
standing in it. Small buildings are now accumulated into 128 m block
masses and drawn as impostors with the right height and the right colour,
so the city still reads as a city from the air while costing a fraction
of the triangles. Up close, flat roofs get parapets and roof clutter —
tanks, stairwell heads, hoardings — and the ground floor of anything on a
retail street gets a shopfront band.

**Facade textures were addressed by absolute world height**, so the
bottom row of windows was sliced wherever a multiple of the 6.2 m tile
happened to fall relative to the pavement. The vertex now carries its own
height-above-base in an eleventh channel.

**And the boats, helicopters and aeroplanes could not be reached.** All
three models were finished and tested and there was no way into one.
Moorings are now derived from the map — piers, ferry wharves, marked
helipads, runways — placed, drawn, blipped, and boarded with the same
button as a car.

The scooter got its own pass. Modelled single-track, with both axles on
the centreline, it had no roll stiffness at all: it flopped twenty-seven
degrees to the *outside* of a corner, tilted its own contact patch, and
pulled 0.25 g, less than a loaded double-decker. What holds a two-wheeler
up is the rider, so the rider is now modelled — a control loop commanding
`tan(lean) = a_lat / g` with the overturning moment fed forward — and the
grip is capped at `tan(40°)`, because a machine that pulls more lateral
g than its lean supports is a machine on its side. It leans into corners
now, at 0.6–0.8 g.

While measuring it, two more: the final drive was clamped at 9:1, which
is not enough reduction for a 250 mm wheel, so the scooter was geared for
63 km/h instead of 85; and engine inertia had a 0.10 kg·m² floor, a
saloon's flywheel, which reflected through a scooter's first gear came to
132 kg·m² against the machine's own 8.9. Nine tenths of a very small
engine was going into spinning itself up. It now reaches 85 km/h and
0–60 in 11.7 s.

| | top | 0–60 | 0–100 | 100–0 | peak lateral |
|---|---|---|---|---|---|
| Kaali-peeli | 135 km/h | 6.8 s | 13.2 s | 44 m | 0.90 g |
| Autorickshaw | 65 km/h | 15.7 s | — | — | 0.82 g |
| Double-decker | 74 km/h | 11.1 s | — | — | 0.92 g |
| Scooter | 85 km/h | 11.7 s | — | — | 0.62 g |
| Sedan | 198 km/h | 6.0 s | 11.4 s | 44 m | 0.90 g |

## Verification

Nothing is published unless all of this passes first:

- **145** world gates in `tools/verify_world.py`, including 51 geographic
  checkpoints, run against the compiled files with an independently derived
  projection. The craft gates re-read the land mask themselves, so a bug in
  the mooring code cannot certify its own output.
- **342** core tests in `core/src/test`, including the same 51 checkpoints
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
