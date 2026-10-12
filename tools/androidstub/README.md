# Local Android API stubs

These are **not** part of the app. They are hand-written, empty-bodied declarations of
the handful of Android framework classes `:android` touches, so the Android sources can
be type-checked on a machine that has no Android SDK:

```sh
ecj -d /tmp/stubout $(find tools/androidstub -name '*.java')
ecj -cp core-classes:/tmp/stubout -d /tmp/aout $(find android/src/main/java -name '*.java')
```

That catches typos, bad signatures and missing imports in seconds instead of waiting for
a CI round trip. The real build in `.github/workflows/android.yml` compiles against the
real `android.jar` from the SDK; these stubs are never on that classpath and are excluded
from every Gradle source set.
