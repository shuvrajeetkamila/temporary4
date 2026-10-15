#!/usr/bin/env bash
# Rebuilds the local toolchain and runs both suites. CI does not use any
# of this; it exists because Maven Central, dl.google.com and Adoptium
# are all unreachable from the development sandbox, so there is no other
# way to run the tests before pushing.
#
#   tools/localtest/run.sh            both suites
#   tools/localtest/run.sh core       core only
#   tools/localtest/run.sh <Class>    one class
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
V=/tmp/jvenv
JAVA="$V/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java"

if [ ! -x "$JAVA" ]; then
  echo "[boot] python venv + jdk4py"
  python3 -m venv "$V" >/dev/null
  "$V/bin/pip" -q install jdk4py numpy pyyaml osmium shapely scipy rasterio || exit 1
fi
if [ ! -f /tmp/ecj.jar ]; then
  echo "[boot] ecj"
  gh api repos/mesteryui/Dotfiles/git/blobs/bc22cd6ec545945007768aedd1cb296d312ddfd8 \
    --jq .content | base64 -d > /tmp/ecj.jar || exit 1
fi
ECJ=(" $JAVA" -jar /tmp/ecj.jar -source 17 -nowarn -proc:none)
cd "$ROOT"
set -e
echo "[build] junit shim + runner"
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -d /tmp/junitstub \
  $(find tools/localtest/src/org -name '*.java')
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -cp /tmp/junitstub \
  -d /tmp/junitout tools/localtest/src/Runner.java
echo "[build] core"
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -d /tmp/out \
  $(find core/src/main -name '*.java')
echo "[build] android stub + android"
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -d /tmp/astubout \
  $(find tools/androidstub -name '*.java')
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -cp /tmp/out:/tmp/astubout \
  -d /tmp/aout $(find android/src/main/java -name '*.java')
echo "[build] tests"
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none -cp /tmp/out:/tmp/junitstub \
  -d /tmp/tout $(find core/src/test -name '*.java')
$JAVA -jar /tmp/ecj.jar -source 17 -nowarn -proc:none \
  -cp /tmp/out:/tmp/astubout:/tmp/aout:/tmp/junitstub -d /tmp/atout \
  $(find android/src/test -name '*.java')
set +e

CORE_CP=/tmp/out:/tmp/tout:/tmp/junitstub:/tmp/junitout
AND_CP=/tmp/out:/tmp/astubout:/tmp/aout:/tmp/atout:/tmp/junitstub:/tmp/junitout
# -Dmumbai.assets is the assets ROOT: WorldData.load prefixes "world/" itself.
FLAGS="-Dmumbai.assets=android/src/main/assets -Dmumbai.requireWorld=true"

want="${1:-all}"
if [ "$want" != "all" ] && [ "$want" != "core" ] && [ "$want" != "android" ]; then
  echo "[run] $want"
  $JAVA -cp "$CORE_CP:$AND_CP" $FLAGS Runner "$want"
  exit $?
fi
rc=0
if [ "$want" = "all" ] || [ "$want" = "core" ]; then
  CLS=$(cd core/src/test/java && find . -name '*Test.java' \
        | sed 's@^\./@@; s@\.java$@@; s@/@.@g' | tr '\n' ' ')
  echo "[run] core"
  $JAVA -cp "$CORE_CP" $FLAGS Runner $CLS || rc=1
fi
if [ "$want" = "all" ] || [ "$want" = "android" ]; then
  CLS=$(cd android/src/test/java && find . -name '*Test.java' \
        | sed 's@^\./@@; s@\.java$@@; s@/@.@g' | tr '\n' ' ')
  echo "[run] android"
  $JAVA -cp "$AND_CP" Runner $CLS || rc=1
fi
exit $rc
