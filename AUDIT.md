# Audit — what was wrong, and what was done about it

> **Status: items 1–9 are fixed.** Each is marked below with what
> actually shipped and what it is measured at now. Items 10–19 are
> long-standing limitations; 12 and 14–17 remain open, and the rest
> cannot be closed from here. The suite is **404 tests, 0 failed,
> 0 skipped**, and **145/145** world gates.

Everything below was **measured**, not recalled. Each item says what was
run, what came back, and what the fix is. Baseline at the time of the
audit: **365 core tests pass, 0 fail, 0 skip**, and **145/145 world
gates**, against the world rebuilt from `data/pinned/`.

Severity:

- **A** — breaks the game as a game. A player meets it in the first minute.
- **B** — visibly wrong, but you can still play.
- **C** — correctness, robustness or hygiene. No player sees it directly.
- **D** — a real limitation that is already known and honestly documented.
  Listed so the picture is complete, not because it is new.

---

## A. Breaks the game

### 1. Nothing in the world is solid. You drive through every building.

> **FIXED.** `CityCollision` indexes footprint rings and bridge decks from the chunk payload already shipped. Push uses each ring's own outward normal; walls are solid from below their drawn base; queries span the 3x3 chunk neighbourhood; contacts are carried into the next physics step. A sedan driven at a building now stops at it, and a person slides along a wall and round its end without ever getting inside. Covered by `CityCollisionTest` and `SolidWorldTest`.


**Severity A. The single biggest gap in the project.**

There is no collision geometry of any kind. `grep -rn "aabb\|AABB\|collide"`
across `core/` and `android/` returns only `ControlMapping` (an unrelated
word match). The only query any physics body makes against the world is
`heightAt(x, z)`, which is the bilinear DEM height field. Buildings never
enter it.

Measured: a sedan started 90 m west of a 33 m building at Kalbadevi
(world −5474, 15191), flat out, heading east. It finished **164 m east of
the building's centre at 120 km/h**, closest approach to the centre
0.2 m. On foot, the same building, same result: the player ended 133 m
past it.

**The fix.** Give `WorldData` a solid-obstacle query backed by the
building rings it already stores, and route every body through it.

1. At compile time, emit a per-chunk obstacle list: for each kept
   building, its axis-aligned bounds, its base and top height, and its
   ring. The data is already in `ChunkFeatures`; it needs an index, not
   new data.
2. Build a uniform grid over it at load (the same 256 m cell scheme
   `Agents` uses for road edges), so a lookup is O(buildings in cell).
3. Add `WorldData.obstacleAt(x, z, y)` returning the penetration depth
   and outward normal of the nearest wall, using point-to-segment
   distance against the ring — not the bounding box, or every courtyard
   becomes solid.
4. In `HumanPhysics`, treat it as a horizontal contact with the same
   compliant-then-rigid law the ground now uses: a spring over the first
   ~0.15 m, with the force capped at what arrests the approach in one
   step. Capsule radius ~0.35 m.
5. In `CarPhysics`, apply the contact at the four corners of the body
   box, so hitting a wall square stops you and clipping it slews you.
   Scale the impulse into `Wanted.rammedVehicle`-style damage reporting
   and into the existing impact sound.
6. Aircraft and boats get the same query; a helicopter should not fly
   through a tower.

**Cost of not fixing it:** it is the difference between a city and a
photograph of one.

---

### 2. Traffic is not solid either. You pass through other cars.

> **FIXED.** Two-body impulse along the line of centres with restitution 0.15, plus 2.6 s of free-rolling for the struck car so `placeOnEdge` cannot snap it back and hide the hit. Measured: the car that was hit is knocked 19 m; closest centre-to-centre approach is no longer 0.1 m.


**Severity A.**

`Agents.update` detects proximity to the player (`HIT_CAR_M = 3.4`) and
uses it to raise the wanted level and trigger the impact sound — but
nothing applies a force to either vehicle.

Measured: the player's sedan was pointed at a traffic car 30 m away and
floored. Closest approach **0.1 m at 47 km/h**; after passing through,
the player was doing **73 km/h** — it had accelerated — and the target
had moved **0.0 m**.

**The fix.**

1. Give `Agents.Car` a mass from its `VehicleKind` and a velocity vector
   (it currently has only `speed` along its edge).
2. On overlap with the player's body, resolve a 2-D impulse along the
   line of centres with restitution ~0.15 and the correct mass ratio;
   apply the equal and opposite impulse to the player body through
   `addForce`.
3. Knock the struck car off its edge into a short free-rolling state
   before it rejoins the graph — otherwise `placeOnEdge` snaps it back
   next frame and the collision is invisible. This is the same trap the
   existing `Agents` tests document.
4. Fold the impulse magnitude into the existing crime/heat path so a
   heavy shunt costs more than a scrape.

---

### 3. Land vehicles drive on the open sea.

> **FIXED.** Deep water returns the seabed, not a floor. Buoyancy and drag in `CarPhysics`, engine drowns when the intake goes under, player bails out and swims after three seconds. The shoreline stays fordable because the 20 m land mask puts the seafront road itself in a water cell. Measured: 7.7 km out at 131 km/h became 47 m and a stop.


**Severity A.**

`Engine.carGround.heightAt` returns `ocean.heightAt(x, z) - 0.35` over
water. That is a solid surface 35 cm below the waves, so a car simply
drives on the sea.

Measured: a sedan driven due west off Marine Drive was **7.7 km out into
the Arabian Sea after 160 seconds, still doing 131 km/h**, riding the
wave surface the whole way (`y` oscillating between −0.12 and +0.47 as
the swell passed under it). It never slowed, never sank.

**The fix.** Water should be a fluid for a wheeled vehicle, not a floor.

1. In `carGround`, stop returning a drivable height over water. Return
   the seabed (`-world.seaDepth(x, z)`), so the wheels find nothing.
2. Add buoyancy and drag to `CarPhysics` when the body is below the
   water line, reusing `BoatPhysics`'s displaced-volume term with a
   car-shaped hull and a deliberately poor one — a car floats badly and
   briefly.
3. Cut engine torque once the intake is under (a drowned engine), and
   ramp up water drag so forward speed collapses in a few metres.
4. Fail the player out of the vehicle the way falling in on foot already
   works: eject and swim, or respawn at the nearest slipway. Shallow
   water near the shore should still be fordable, which
   `world.seaDepth` already gives for free.

---

### 4. Nothing is saved. Every launch starts from zero.

> **FIXED.** `Progress` in core over a `Store` interface, `PrefsStore` over SharedPreferences on Android, saved on pause and loaded before the spawn. Money, jobs by kind, best race time, times busted, the clock and the last position. A corrupt save is a new game, not a crash.


**Severity A for "progression", which the brief asks for by name.**

There is no `SharedPreferences`, no save file, no serialisation anywhere
in `android/src/main/java`. `Missions.money` is a plain `int` on a
`Missions` that is constructed fresh with every `Engine`. Measured: money
on a brand-new `Engine` is 0, and there is no code path that could make
it anything else on a second launch.

So fares earned, jobs completed, the time of day and the player's
position all vanish when the activity is destroyed — which Android does
routinely on rotation or memory pressure, not just on exit.

**The fix.**

1. A small `Progress` value object in core: money, jobs completed per
   kind, best race times, total distance, the last spawn point, the
   in-game clock.
2. Serialise it as JSON (there is already a JSON reader in the data
   path) through an interface in core, implemented in `android` over
   `SharedPreferences` — core stays free of Android types, as it is now.
3. Save on `onPause` and on every job completion; load in `onCreate`
   before `spawnAt`.
4. Add a round-trip test in core: earn, save, reload, assert the money
   and the stage survived.

---

## B. Visibly wrong

### 5. The sun never goes below the horizon.

> **FIXED.** One clamp removed. `sunDir.y` now runs -0.999 at midnight to +0.999 at noon, crossing zero at six.


**Severity B.**

`Sky.evaluate` computes the elevation correctly — it runs from −57° at
midnight to +57° at noon — and then throws it away:

```java
sunDir[1] = (float) Math.max(0.045, elev);
```

The direction vector handed to the shader is clamped above the horizon
for ever. Measured across the day:

| hour | true elevation | `sunDir.y` fed to the shader |
|---|---|---|
| 00:00 | −57.3° | **+0.731** |
| 04:30 | −21.9° | +0.052 |
| 12:00 | +57.3° | +0.999 |
| 19:30 | −21.9° | +0.052 |
| 21:00 | −40.5° | **+0.085** |

So at midnight the scene is lit from 47° above the horizon, and the
"sun" *climbs* through the night instead of setting. The night sun
colour is nearly black — (0.04, 0.05, 0.09) — so the error is dim rather
than glaring, but the light direction is wrong all night, and at 19:30
the world still takes a 0.40-intensity reddish key light from just above
the horizon when the sun is 22° under it.

**The fix.** Let it set. Use the true elevation in the vector, and lean
on the ambient and sky terms for night lighting, which already exist:

```java
sunDir[1] = (float) elev;
```

and make the horizontal component keep its length as the sun goes down
(the current `Math.max(0.12, …)` floor on `horiz` is there to stop the
vector degenerating and can stay). Then verify in `SkyTest` that
`sunDir.y` is negative for every hour where `sunElevation` is negative,
and that the moment of crossing is continuous.

---

### 6. There are no shadows at all.

> **FIXED, as blobs.** One dark disc per body in three sizes, drawn before the traffic with depth writes off, faded by sun elevation and by night. Buildings still cast none.


**Severity B.** Not a regression — it has never existed — but it is the
largest single thing separating the look from the PS2-era games the
brief points at. Those games had, at minimum, a blob under every vehicle
and pedestrian.

**The fix, cheapest first.**

1. **Blob shadows.** One extra draw per agent: a camera-facing dark
   ellipse projected onto the height field under each vehicle, person
   and parked car, faded by height above ground and by `nightFactor`.
   A few hundred triangles for the whole scene, no new render target.
2. **Static building shadows,** if there is budget: bake a per-chunk
   shadow mask at compile time for a handful of sun azimuths and blend
   between them by hour. Costs world size, buys a lot.
3. A real shadow map is almost certainly not affordable at the frame
   budget this targets and should not be attempted first.

---

### 7. The chase camera clips through everything.

> **FIXED.** The camera marches out from the player's head in 1 m steps and stops short of the first wall.


**Severity B, and a direct consequence of #1.**

`Engine.cameraPosition` pushes the camera back along the view vector and
then applies exactly one correction: `if (out.y < groundHeight + 1.1)`.
It is protected against sinking into a hill and against nothing else.
With no building collision it sits inside walls constantly in dense
areas.

**The fix.** Once #1 lands, sweep the camera: march from the player's
head to the desired camera position in ~1 m steps, stop at the first
obstacle hit, and pull in to just short of it. Keep the existing ground
clamp. This is a handful of lines on top of the obstacle query and is
the main reason to do #1 first.

---

## C. Correctness and hygiene

### 8. `AudioMixer.render` can write past the end of its buffer.

> **FIXED.** Clamped to `out.length / 2`. Silence beats a dead audio thread.


**Severity C — not currently live, but one careless caller away.**

The signature is `render(short[] out, int frames)` and the body writes
`out[i * 2]` and `out[i * 2 + 1]`, so `out` must be `2 × frames` long.
Nothing checks it. Calling `render(buf, buf.length)` — the obvious
reading of the signature — throws
`ArrayIndexOutOfBoundsException: Index 1024 out of bounds for length 1024`
from `AudioMixer.java:251`. I hit this writing the audit probe.

The one real caller, `AudioOutput`, is correct: `new short[FRAMES * 2]`
then `render(buf, FRAMES)`.

**The fix.** Either guard it —

```java
int n = Math.min(frames, out.length / 2);
```

— or rename the parameter and document it as stereo frames. A one-line
test that calls it with a too-small buffer and expects no crash.

---

### 9. The README's autorotation claim is wrong, and understates the model.

> **FIXED.** Text corrected and the numbers pinned in `FlightControlsTest`: 11.1 m/s flat pitch, 7.3 m/s at 30 % collective.


**Severity C — an honesty defect, in the direction of being too harsh on
itself, which still has to be fixed.**

README says the engine-off descent is "about twice as steep as a real
one". Measured, entering autorotation properly — trimmed, 100 km/h
forward, then engine off:

| collective | descent | rotor |
|---|---|---|
| 0.00 | 11.1 m/s | 443 rpm |
| 0.10 | 9.3 m/s | 385 rpm |
| 0.20 | 8.1 m/s | 339 rpm |
| 0.30 | 7.3 m/s | 304 rpm |

A real light helicopter autorotates at roughly 12 m/s. The model is
**correct, and slightly optimistic if anything.** The earlier figure came
from a bad test: a vertical autorotation from a hover with no airspeed,
which in a real helicopter is also a very bad day.

**The fix.** Replace the sentence with the measured numbers and the
technique they assume. Add the table above to `AircraftPhysicsTest` as
an assertion band so the claim cannot rot again.

*(Checked and found healthy while here: the aeroplane. Rotating at
1.1 × stall for each configuration, the ground roll is 894 m clean,
740 m at 10° flap, 591 m at 25° — flaps shorten the roll, as they must.
Stall flag triggers; slowest speed 82 km/h. An earlier reading that
"flaps lengthen the takeoff" was my test holding rotation speed fixed
across configurations.)*

---

## D. Known limitations, still true

These are already in `DELIVERY.md`. Listed for completeness; none is new.

10. **No real-hardware frame rate or thermal data.** No physical device
    has ever been available. The only GPU this has run on is
    SwiftShader. Fix: run it on a phone; nothing else will do.
11. **Touch controls have never been used by a human.** They are driven
    by unit tests. Fix: same as 10.
12. **Debug signing key.** Fix: generate a release keystore, hold it in
    GitHub Actions secrets, sign in CI. The private key must never enter
    the repository.
13. **No interiors.** Every building is a sealed exterior. Fix: out of
    scope for a procedural city at this scale; would need hand-authored
    volumes.
14. **No transit boarding.** Trains, metro, monorail and ferries run
    between the ends of their lines and cannot be ridden; the 380 OSM
    platform ways are not modelled. Fix: platform proximity trigger plus
    a camera-attached-to-carriage state.
15. **No on-foot police.** The response is vehicles and arrest. Fix: a
    pedestrian agent variant that pursues and uses the existing baton.
16. **85.7 % of building heights are inferred,** not surveyed.
    14.3 % carry an OSM tag. Fix: no better free source exists for
    Mumbai; Copernicus GLO-30 was tried and cannot resolve dense urban
    morphology.
17. **The full-screen map's coastline is chunky** — it is drawn from a
    72-column land mask. Fix: raise the mask resolution for the map
    layer, or draw the coastline from the vector rings already in the
    world file.
18. **Upper Thane and Vasai Creek are land,** because OSM maps them as
    intertidal mudflat. Not navigable in this build.
19. **No music, no voices, no footsteps.** Everything audible is
    synthesised at runtime, which keeps it original but limits it.

---

## Suggested order

1 → 7 → 2 → 3 (the collision family; 7 is nearly free once 1 exists, and
2 and 3 reuse the same contact-resolution code), then 4, then 5, 8 and 9
as a small batch, then 6.

Items 1–3 are the difference between a world you look at and a world you
can hit.


---

## Added after the audit, at the user's request

**20. Bridges were render-only.** Not in the original list because the
audit probed buildings, not spans. The mesher arches a bridge deck over
the water and the ground probe returned the seabed beneath it, so every
bridge in the city - the Sea Link, the harbour jetties, every flyover -
was something you drove through. The deck formula now lives in one place,
`RoadDeck`, and both the mesher and the collision index call it, so they
cannot drift apart. Measured: more than twenty decks found across the
city, the highest standing well clear of the ground under it, and a body
beneath a bridge with real headroom is not handed the deck.

**21. Both aircraft had the stick in backwards.** `lookY` is a screen
axis, negative for the stick pushed forward, and both aircraft negated it
again: cyclic forward pitched the helicopter's nose up and flew it
backwards, and stick forward made the aeroplane climb. Every existing
test drove the physics fields directly and could not see it.

**22. The aeroplane's rudder was flying the aeroplane.** Three seconds of
pedal gave 51 degrees of bank for 29 of yaw, because the dihedral term
was about three times too strong. Now 22 for 34, with the sideslip still
washing out to a hundredth of a degree.
