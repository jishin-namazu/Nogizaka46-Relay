# Haze Glass 2.0.0 Ambient Reflection Correction

Haze's `GlassShaders.opticalHelpers().applyAmbient()` multiplies the sampled
color by its Fresnel ambient response, then clips SDR channels to `[0, 1]`.
With this app's light backdrop and milky tint, that clips a broad perimeter
to white. The unlit center remains lavender, leaving an apparent rectangular
inset in every card. The card silhouette is correctly clipped; restricting
capture bounds does not address the lighting defect.

The patch mixes reflected white light with transmitted color using the
bounded Fresnel response. It preserves the normal field, alpha, hue and
extended-range color behavior without clipping light SDR colors into a
white plateau. Background blur, edge refraction, interaction lighting and
expanded sampling bounds remain enabled.

`gradle/haze-glass-patch.gradle.kts` applies the checked-in AGSL change to
the embedded shader string in the upstream AAR at dependency resolution.
It changes no class methods, capture logic, blur kernels or refraction math.
Other AARs pass through unchanged. The transform is cacheable, normalizes
patch file line endings and refuses a changed upstream artifact or anything
other than exactly one matching source fragment.

Upstream: https://github.com/chrisbanes/haze (Apache-2.0)

Original source: `haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassShaders.kt`

Pinned artifact: `dev.chrisbanes.haze:haze-glass-android:2.0.0`

SHA-256: `2b06240d6b5752c5e32aa03fa94ab1f62c71715aeec714bfd50e09384c38f68f`

## Verification

Hardware rendering requires Android 13 or newer. Always build the isolated
package; device test runners can uninstall their target package after tests.
The main package contains user data and must never be a test runner target.

```powershell
./gradlew.bat :app:assembleDebug -PrelayRenderVerification=true
adb install -r app/build/outputs/apk/debug/app-rendercheck-debug.apk
```

This builds an isolated app for manual inspection. Instrumented regression
tests are local-only: `test/`, `tests/`, `androidTest/` and `app/src/test/`
are ignored by Git, so a fresh clone does not include `GlassRenderingTest`.
Only run the following commands when that local test suite is present:

```powershell
./gradlew.bat :app:assembleDebugAndroidTest -PrelayRenderVerification=true
adb install -r app/build/outputs/apk/androidTest/debug/app-rendercheck-debug-androidTest.apk
adb shell am instrument -w -r -e class com.nogirelay.app.ui.glass.GlassRenderingTest com.nogirelay.app.rendercheck.test/androidx.test.runner.AndroidJUnitRunner
```

The isolated package has no Firebase configuration. It never registers as a
push device. When available, the local tests compare GPU pixel output for a
light uniform backdrop,
striped source blur, displaced refraction and a moving source. They preserve
PNG captures in the isolated package's external `files/glass-verification`
directory for inspection. The regression rejects channel-clipped highlight
plateaus while asserting that ambient reflection remains active.

Ordinary app builds do not use `relayRenderVerification`; their Firebase
configuration and package name remain as configured for production.
