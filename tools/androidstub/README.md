Minimal Android API surface, for type-checking the `:android` module on a
machine with no Android SDK. It is a compile-time aid only: it is never on
the Gradle classpath and never reaches the APK. If a call here does not
exist in the real framework the stub will happily accept it, so a green
local compile is a smoke test, not a guarantee.
