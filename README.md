Smart Mirror is a Kotlin Multiplatform app for Android and iOS. All business logic is shared in Kotlin, and each platform has its own native UI. The architecture, module map and platform contracts are in [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md). The product scope is in [docs/ROADMAP.md](./docs/ROADMAP.md).

* [/sharedLogic](./sharedLogic/src) is the KMP library for Android and iOS (framework `SharedLogic`). It holds the domain models, the platform port interfaces, settings persistence, the mirror state holder (`MirrorViewModel`, MVI) and the frame-processing control plane.
  - `commonMain`: shared code. `androidMain` / `iosMain`: platform storage (`SharedPreferences` / `NSUserDefaults`).
  - `commonTest`: reducer, ViewModel, repository and pipeline tests with fakes.
* [/androidApp](./androidApp/src) is the Android app. Its UI is Jetpack Compose, and it depends on `:sharedLogic`.
* [/iosApp](./iosApp/iosApp) is the iOS app. Its UI is SwiftUI, and it links `SharedLogic.framework` through the `:sharedLogic:embedAndSignAppleFrameworkForXcode` build phase.

### Building

Gradle's daemon runs on JDK 21 (see `gradle/gradle-daemon-jvm.properties`). To launch the wrapper, point `JAVA_HOME` at a JDK 17+, for example:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open [/iosApp](./iosApp) in Xcode and run it, or build it with `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build`
- iOS framework only: `./gradlew :sharedLogic:linkDebugFrameworkIosSimulatorArm64`

### Tests

- All shared tests (JVM + iOS simulator): `./gradlew :sharedLogic:allTests`
- JVM only (fast): `./gradlew :sharedLogic:testAndroidHostTest`
- iOS simulator only: `./gradlew :sharedLogic:iosSimulatorArm64Test`
