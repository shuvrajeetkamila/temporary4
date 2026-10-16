# Testing guide — which APK, and what to look for

**Install v2.0.60 only. Keep v2.0.50 as a fallback you install *only if* v2.0.60 will not start.**

| | v2.0.50 | v2.0.60 |
|---|---|---|
| Launches on an emulator | yes, verified | **CI emulator dies — cause unknown** |
| Camera when you enter a car | **broken** (looks at the bonnet from in front) | fixed |
| Ground turning white after a while | **broken** | fixed |
| Trains / metro / monorail visible | **never drawn** | fixed |
| Pedestrians and aircraft visible | **never drawn** (culled to zero) | fixed |
| Helicopter collective | latches at full power | springs back to a hover |

Testing v2.0.50 again would only re-confirm the four faults you already found. The one
thing nobody knows is whether **v2.0.60 starts on real hardware**, so that is the test.

---

## 1. Install

Download on the phone itself:

<https://github.com/shuvrajeetkamila/temporary4/releases/download/v2.0.60/Mumbai-The-Island-City-v2.0.60.apk>

- `Mumbai-The-Island-City-v2.0.60.apk`
- 14,912,932 bytes
- SHA-256 `58836fbaa8ddf1057f5d7dbc2a2462e9a290b5409baf9d5c6f31352eef136608`
- package `ai.shuvrajeetkamila.mumbai.islandcity`, versionCode 60, minSdk 26 (Android 8.0), targetSdk 34

**Uninstall the old build first.** Long-press the icon → App info → Uninstall. Every CI run
generates its own debug signing key, so Android will refuse to install one version over
another with *"App not installed"* / *"package conflicts with an existing package"*.
Uninstalling first avoids that, and gives a clean save.

Then: open the downloaded file → allow *"Install unknown apps"* for your browser when asked
→ Play Protect will warn that the developer is unknown (it is debug-signed and not from the
Play Store) → **Install anyway**.

The game is landscape only. No network permission is declared; the whole world is inside
the APK.

---

## 2. The one question that matters

**Does it get past a black screen to a view of the city?**

If yes → go to section 3.
If no → section 4. Do not spend time on anything else.

---

## 3. If it launches — six things to check

The debug panels are always on screen: speed/position top-left, streaming and counts
top-right, radar bottom-right.

1. **People.** Top-right it says `… cars … people`. In v2.0.50 that number was non-zero and
   you still saw nobody — the renderer was throwing them away. Now you should actually
   **see people walking on the pavements**. Same for aircraft in the sky.

2. **Ground stays solid.** Play continuously for 10 minutes — walk, drive, go fast, go far.
   The bug was that the ground turned white and only a full app restart brought it back.
   If it happens, note the top-right numbers: `tiles N active / M resident` and
   `io / decode / gpu`.

3. **Car camera.** Walk up to a parked car, press **Enter**. The camera must settle
   **behind the car, looking over the roof**, and pressing accelerate must drive you *away*
   from the camera. Before, it sat in front of the bonnet looking back, so the car appeared
   to drive at you and steering felt mirrored.

4. **Trains.** Stand on a railway/metro/monorail platform. The arrival timer was already
   counting down before; now a **train should actually arrive and run along the track**.

5. **Helicopter.** Find a parked helicopter. Hold the climb button — the HUD shows `coll%`
   and `m/s up`. **Let go**: it should ease back to a hover instead of climbing forever.
   Try forward cyclic, then the yaw pedals.

6. **Smoothness and heat.** FPS is top-left. Tell me the number, whether it stutters, and
   whether the phone gets hot after ~10 minutes. I have **no measured numbers from real
   hardware** — everything so far is from an emulator, so your reading is the first real one.

---

## 4. If it does not launch

Tell me, as precisely as you can:

- Phone model and Android version
- Which of these happened:
  - icon bounces and nothing opens
  - black screen, then back to the home screen
  - *"Mumbai: The Island City keeps stopping"*
  - white screen that never goes away
  - it opens, shows the city, then dies after a few seconds
- Roughly how many seconds it survived
- Whether a second attempt behaves the same

Then reinstall **v2.0.50** so you still have something playable
(uninstall v2.0.60 first — same signing-key reason):

<https://github.com/shuvrajeetkamila/temporary4/releases/download/v2.0.50/Mumbai-The-Island-City-v2.0.50.apk>

- 14,911,520 bytes
- SHA-256 `86f604db55d1e3942aed4b6677b9f40f5e791f7a68336eca5553d72893545506`

---

## Why v2.0.60 is not marked verified

The CI emulator runs on **SwiftShader**, a software GPU. With v2.0.60 the app itself is
healthy in the log — all 5 shaders compile, the world reports ready, audio is up, draw calls
are being issued, frame 1 completes — and then partway through frame 2 the **emulator
process itself** disappears. No Java exception, no crash log, no device. That is a signature
of a fault inside the software renderer, not of an app crash, but it cannot be proven from
inside CI.

Ruled out, one CI run each: host memory, guest RAM 4096→2560 MB, the transit draw (removed —
still died), instance-count overruns (all draws clamped; nothing was ever over), non-finite
vertex data (144,540 pose floats checked, 0 bad), resident chunk budget 420→280→160, and a
768 KB/frame GPU upload cap. Frames 1 and 2 draw the same groups and only the second is
fatal, so it is not any one draw call.

Your phone has a real GPU. If it launches there, the bug is in the test rig and I will fix
the rig. If it does not, I will bisect by disabling the newly-drawn groups one run at a time.
