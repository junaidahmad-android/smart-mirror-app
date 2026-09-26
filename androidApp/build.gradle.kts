import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    // All business logic lives in KMP; UI here is Jetpack Compose (Android-only).
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.materialIconsExtended)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    // Camera (platform adapter for the shared CameraController port).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) {
        // Stills-only app: camera-video (and its media3 stack, which merges ACCESS_NETWORK_STATE
        // into the manifest, QA-BUG-05) is only used by camera-view's CameraController, not by
        // PreviewView. Excluding it keeps the app free of network permissions and ~video code.
        exclude(group = "androidx.camera", module = "camera-video")
    }

    debugImplementation(libs.androidx.compose.uiTooling)
    debugImplementation(libs.androidx.compose.uiTestManifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.testJunit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.uiTestJunit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.extJunit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.kotlin.testJunit)
    // Android Test Orchestrator: each test runs in its own instrumentation process and, with
    // clearPackageData, starts from wiped app data (no settings leaking between tests).
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}

// Release signing comes from the environment (CI secrets). When the variables are absent the
// release build stays unsigned, so local `bundleRelease` / `assembleRelease` keep working.
val releaseKeystorePath: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).isFile
// CI sets these from the tag / run number; local builds keep the defaults below.
val ciVersionCode: Int? = providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.toIntOrNull()
val ciVersionName: String? = providers.environmentVariable("ANDROID_VERSION_NAME").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.smart.mirror.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.smart.mirror.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // With the orchestrator: `pm clear` the app before every test (hermetic settings/storage).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8 keeps the download small (NFR-09): it strips the unused material-icons-extended icons.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        animationsDisabled = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}
