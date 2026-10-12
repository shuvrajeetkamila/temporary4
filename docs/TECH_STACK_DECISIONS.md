# Technology decisions, and where this build deviates from the brief

The brief suggested libGDX with gdx-bullet as a reference stack. This build does not
use it. Everything below explains what was built instead and why, so the deviation is
on the record rather than discovered later.

---

## 1. Engine: hand-written, not libGDX

| Layer | Choice |
|---|---|
| Language | Java 17 |
| Modules | `:core` (pure Java, no Android imports) and `:android` |
| Rendering | OpenGL ES 3.0 directly, through `GLSurfaceView` |
| Physics | Custom rigid-body + per-system force models |
| UI / HUD | `android.graphics.Canvas` on a `View` above the GL surface |
| Build | Gradle 8.9, AGP 8.5.2 |
| Runtime dependencies | **none** |

### Why not libGDX + Bullet

1. **The physics brief is specific and non-generic.** The requirement is per-wheel
   spring-damper suspension with a torque curve and slip curves, 6–12 point hull
   buoyancy against analytic waves, blade-element rotor thrust with induced inflow and
   autorotation, and `q = ½ρv²` aerodynamics with stall and flaps. A general rigid-body
   solver gives you contacts and constraints — it does not give you any of that. Every
   one of those models has to be written either way, and writing them against a plain
   integrator is simpler than writing them against Bullet's callback API.

2. **A 44 × 65 km double-precision world.** The simulation keeps world positions as
   `double` and rebases a floating origin every kilometre. Bullet is single precision
   throughout; bolting a rebase onto it means re-homing every body and every broadphase
   proxy on each rebase, which is exactly the class of bug that produces silent
   teleports. Owning the integrator makes the rebase a one-line coordinate shift that
   `FloatingOriginTest` can prove is lossless.

3. **Streaming is the hard part, and it is ours either way.** The state machine,
   worker decode, bounded GPU upload, cancellation, eviction and memory budget are
   project code regardless of the engine. libGDX would contribute asset loading we
   cannot use, because the world is five binary layers plus procedural geometry.

4. **Size and startup.** No runtime dependency means the APK is the city plus about
   8,000 lines of our own classes, and there is no third-party initialisation on the
   critical path to first frame.

5. **Testability.** `:core` has no Android imports, so the entire simulation — world
   sampling, projection, all five physical systems, the streamer, the fixed-step loop
   — runs and is asserted on a desktop JVM in under five seconds. That is what makes
   the physics numbers in this build defensible rather than hopeful.

### What we give up

* No ready-made collision solver: collision is an analytic height field plus
  per-building AABBs, which is enough for the five systems in scope but would need
  replacing for ragdoll-vs-world or vehicle-vs-vehicle contacts.
* No cross-platform desktop target.
* No skeletal animation system; the human is a capsule with a procedural mesh.

---

## 2. Fixed-step simulation

```
STEP          = 1/60 s
MAX_SUBSTEPS  = 4
frame dt      clamped to 0.1 s
leftover      -> alpha, used for render interpolation only
```

The accumulator never spirals: if four substeps are not enough to catch up, the
backlog is dropped rather than compounded. Rendering interpolates position linearly
and orientation with a shortest-arc quaternion lerp. Teleports (mode switches,
respawns) call `snapshot()` twice so interpolation cannot smear across them.
`EngineLoopTest` asserts all of this, including that a 3-second stall produces at most
four physics steps.

Units are SI throughout: metres, seconds, kilograms, newtons, radians. Display
conversions (km/h, knots, feet) happen only in the HUD.

---

## 3. Threading

| Thread | Owns |
|---|---|
| GL thread | `MumbaiRenderer` → `GameHost` → `Engine`; all GL objects; the physics step |
| `mumbai-stream-N` (daemon, `NORM_PRIORITY-1`) | Chunk decode into plain float arrays |
| `mumbai-load` (daemon) | One-time world load |
| UI thread | `HudView`, reading a volatile `Telemetry` snapshot at ~15 Hz |

`Telemetry` is the only channel from simulation to UI and every field is volatile and
independently meaningful, so a torn read can produce a stale number but never a crash.
Workers never touch GL; `TileSink.onTileReady` is explicitly documented as a
worker-thread notification only.

---

## 4. Rendering

* One directional sun, hemispheric ambient fill, exponential-squared distance fog.
* Vertex format is fixed at 10 floats — position, normal, albedo, aux — and
  `MeshBuilder.STRIDE` is the single source of truth for both the builder and the
  attribute pointers.
* Terrain tiles are 16 × 16 quads at every LOD; the LOD pyramid is 256 m / 1 km / 4 km.
* Ambient traffic is drawn instanced, one call per vehicle kind.
* The sea is one camera-following grid whose vertex shader evaluates **the same wave
  trains** `WaterModel` hands the buoyancy solver, with the floating-origin phase
  folded in on the CPU at double precision. The swell you see is the swell you float
  on, not a lookalike.
* Inland water is real geometry at `bed + class depth`, emitted by `ChunkBuilder`,
  because lakes in the national park are 50+ m above the tide and cannot share the
  sea plane.

---

## 5. Honest limits of this build

These are stated here and in the release notes rather than buried:

* **Ambient traffic and pedestrians are kinematic.** Only the player's vehicle is
  force-based. Crowd and traffic agents follow road splines with speed and spacing
  rules; they do not have suspension or tyre models. This is a deliberate budget
  decision, not an oversight.
* **No measured device performance.** The frame caps (30 / 20 / 15 FPS) and the eco
  tiers are implemented and the work reductions are unit-tested, but no FPS or thermal
  figure from a real handset is claimed anywhere, because none was measured in this
  environment.
* **Collision is height field + building AABBs.** There is no mesh-accurate collision
  and no vehicle-to-vehicle physics.
* **Terrain elevation is synthetic.** See `DATA_SOURCES.md` §4.
* **No audio.**
* The renderer has no shadow pass yet; the `shadows` flag in `QualityProfile` is
  plumbed through and currently selects a slightly richer ambient term rather than a
  shadow map.

---

## 6. Identifiers

```
applicationId   ai.shuvrajeetkamila.mumbai.islandcity
namespace       ai.shuvrajeetkamila.mumbai
minSdk          26   (Android 8.0)
compileSdk      34
targetSdk       34
AGP             8.5.2
Gradle          8.9
```
