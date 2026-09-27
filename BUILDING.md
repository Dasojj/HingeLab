# Building Hinge Lab

Use JDK 17 or newer (the tested local build uses Android Studio's bundled JBR),
Android SDK Platform 37 and the checked-in Gradle 8.13 wrapper. Set ANDROID_HOME
to your SDK directory, or set sdk.dir in a local.properties file. AGP is 8.13.2;
Kotlin is 2.4.0. The repositories in settings.gradle.kts are needed for build
tools and dependencies; the source bundle is not an offline SDK distribution.

Windows:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

Linux/macOS:

```sh
sh gradlew testDebugUnitTest assembleDebug
```

Output: app/build/outputs/apk/debug/app-debug.apk.

The distributed experimental APK remains a debug build to preserve the tested
bootstrap and update path. Your local debug key will normally differ from the
author's key, so your build may require uninstalling the existing APK first.
Stop the animation and shell helper from Hinge Lab before uninstalling.
Uninstalling clears application data and permissions. Signing private keys are
not included in the source bundle.

2.0-beta.15 preserves the selected 2.0-beta.4 display/angle behavior and default renderer,
with an opt-in perspective-and-blur renderer. Earlier publication changes: licenses,
notice, version metadata, the license viewer, community links, launcher icon and setup guidance changed. Later experimental
beta.5–7 animation changes are not incorporated. Automated tests do not replace
testing setup, folding, locking and updating on a physical Samsung device.

See LICENSING.md, third_party/DEPENDENCIES.md and the dependency-sources
manifest in the release bundle for dependency provenance and source material.
