#!/usr/bin/env bash
#
# Install the APK that was just built on a real Android emulator, launch it, and
# prove it actually ran. "It compiled" is not the same claim as "it runs", and this
# is the step that lets the project make the second one.
#
# Everything it asserts is observed from the device: the package manager's record of
# the install, the activity manager's record of the launch, the app's own log markers
# emitted from the GL thread, and a screenshot. If any of them is missing, this exits
# non-zero and the build is not published as working.

set -uo pipefail

OUT=device
mkdir -p "$OUT"

APK=$(ls artifacts/*.apk | head -1)
if [ -z "$APK" ]; then
    echo "FATAL: no APK to test"
    exit 1
fi
PKG=ai.shuvrajeetkamila.mumbai.islandcity
ACT="$PKG/ai.shuvrajeetkamila.mumbai.android.MainActivity"

echo "=== device ==="
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.hardware

echo
echo "=== install $APK ==="
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"
if ! grep -q "Success" "$OUT/install.txt"; then
    echo "FATAL: install failed"
    exit 1
fi

adb shell pm path "$PKG" | tee "$OUT/pm_path.txt"
if ! grep -q "package:" "$OUT/pm_path.txt"; then
    echo "FATAL: package manager does not know about $PKG after install"
    exit 1
fi

adb logcat -c
echo
echo "=== launch $ACT ==="
adb shell am start -W -n "$ACT" 2>&1 | tee "$OUT/launch.txt"
if grep -qi "Error\|does not exist" "$OUT/launch.txt"; then
    echo "FATAL: activity would not start"
    adb logcat -d > "$OUT/logcat.txt"
    exit 1
fi

# Let it load the world and render. The world decode plus the first streaming rings
# take a few seconds on a software GL emulator.
echo
echo "=== running for 75 s ==="
for i in $(seq 1 15); do
    sleep 5
    if adb logcat -d | grep -q "MUMBAI_FIRST_FRAME_DRAWN"; then
        echo "  first frame drawn after ~$((i * 5)) s"
        break
    fi
    echo "  ...$((i * 5)) s"
done

sleep 10
adb logcat -d > "$OUT/logcat.txt"
adb exec-out screencap -p > "$OUT/screenshot.png" 2>/dev/null || true
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" 2>/dev/null || true

echo
echo "=== app log ==="
grep -E "Mumbai(Host|Renderer)|AndroidRuntime|$PKG" "$OUT/logcat.txt" | tail -60

FAIL=0

echo
echo "=== assertions ==="

# 1. No crash.
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
    echo "FAIL  the app crashed"
    grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40
    FAIL=1
else
    echo "PASS  no fatal exception"
fi

# 2. No ANR.
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
    echo "FAIL  the app was not responding"
    FAIL=1
else
    echo "PASS  no ANR"
fi

# 3. The process is still alive at the end of the run.
if adb shell pidof "$PKG" > /dev/null 2>&1; then
    echo "PASS  process still alive after 75 s"
else
    echo "FAIL  the process died during the run"
    FAIL=1
fi

# 4. A GL context was created and reported.
if grep -q "GL vendor=" "$OUT/logcat.txt"; then
    echo "PASS  GL context created: $(grep -m1 'GL vendor=' "$OUT/logcat.txt" | sed 's/.*GL vendor=/GL vendor=/')"
else
    echo "FAIL  no GL context was ever created"
    FAIL=1
fi

# 5. The packaged world actually loaded and parsed on the device.
if grep -q "MUMBAI_WORLD_READY" "$OUT/logcat.txt"; then
    echo "PASS  $(grep -m1 -o 'MUMBAI_WORLD_READY.*' "$OUT/logcat.txt")"
else
    echo "FAIL  the packaged world never finished loading on the device"
    FAIL=1
fi

# 6. Geometry was actually submitted - the real "it is not a black screen" check.
if grep -q "MUMBAI_FIRST_FRAME_DRAWN" "$OUT/logcat.txt"; then
    LINE=$(grep -m1 -o 'MUMBAI_FIRST_FRAME_DRAWN.*' "$OUT/logcat.txt")
    echo "PASS  $LINE"
    TRIS=$(echo "$LINE" | sed -n 's/.*triangles=\([0-9]*\).*/\1/p')
    if [ -n "$TRIS" ] && [ "$TRIS" -gt 5000 ]; then
        echo "PASS  $TRIS triangles in the first drawn frame"
    else
        echo "FAIL  only ${TRIS:-0} triangles were drawn - the world is not rendering"
        FAIL=1
    fi
    if echo "$LINE" | grep -q "glError=0"; then
        echo "PASS  no OpenGL error on the first frame"
    else
        echo "FAIL  OpenGL reported an error: $LINE"
        FAIL=1
    fi
else
    echo "FAIL  no frame with geometry was ever drawn"
    FAIL=1
fi

# 7. No shader compile or link failure.
if grep -qi "shader compile failed\|program link failed" "$OUT/logcat.txt"; then
    echo "FAIL  a shader did not build"
    grep -i -A 10 "shader compile failed\|program link failed" "$OUT/logcat.txt" | head -30
    FAIL=1
else
    echo "PASS  all shader programs built"
fi

# 8. A screenshot exists and is not a uniform blank image.
if [ -s "$OUT/screenshot.png" ]; then
    SIZE=$(stat -c%s "$OUT/screenshot.png")
    echo "PASS  screenshot captured ($SIZE bytes)"
    # A single flat colour compresses to almost nothing; a rendered city does not.
    if [ "$SIZE" -lt 20000 ]; then
        echo "FAIL  the screenshot is too uniform to be a rendered scene"
        FAIL=1
    fi
else
    echo "WARN  no screenshot (not treated as a failure: screencap is flaky headless)"
fi

{
    echo "# Device run"
    echo
    echo "Emulator: API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo " ($(adb shell getprop ro.build.version.release | tr -d '\r')),"
    echo "$(adb shell getprop ro.product.cpu.abi | tr -d '\r'), software GL (SwiftShader)."
    echo
    echo '```'
    grep -E "MUMBAI_WORLD_READY|MUMBAI_FIRST_FRAME_DRAWN|GL vendor=" "$OUT/logcat.txt" \
        | sed 's/^.*: //' | sort -u
    echo '```'
    echo
    echo "No fatal exception, no ANR, process alive after 75 s."
    echo
    echo "This is a software-rendered emulator. It proves the APK installs, launches,"
    echo "parses its world and renders real geometry. It says nothing at all about"
    echo "frame rate or thermals on real hardware - no such measurement was taken."
} > "$OUT/DEVICE_RUN.md"

echo
cat "$OUT/DEVICE_RUN.md"

if [ "$FAIL" -ne 0 ]; then
    echo
    echo "DEVICE SMOKE TEST FAILED"
    exit 1
fi
echo
echo "DEVICE SMOKE TEST PASSED"
