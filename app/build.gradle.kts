import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Per-machine config + secrets from local.properties (gitignored) so they never land
// in version control. CI supplies the same keys via environment variables, so
// [secret] checks local.properties first, then the environment.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun secret(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").trim()

// MapLibre style URL (any provider key baked in). Blank → the UI shows a placeholder gate.
val mapStyleUrl = secret("MAP_STYLE_URL")
// GraphHopper hosted Directions/Geocoding key. Blank → routing/search report not-configured.
val graphHopperApiKey = secret("GRAPHHOPPER_API_KEY")

// Release signing material (gitignored keystore + passwords, or CI env vars). When
// unset, the release build falls back to the debug key so `assembleRelease` still
// produces an installable APK for local smoke-testing — CI/production must set these
// four keys to ship a properly-signed build.
val releaseStoreFile = secret("RELEASE_STORE_FILE").ifBlank { null }

android {
    namespace = "com.example.displayapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.displayapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Exposed via BuildConfig so the map layer can pick up the style URL and
        // detect "no style configured" to show a placeholder. MapLibre needs no
        // manifest API key (unlike the Maps SDK), so there's no manifest placeholder.
        buildConfigField("String", "MAP_STYLE_URL", "\"${mapStyleUrl}\"")
        buildConfigField("String", "GRAPHHOPPER_API_KEY", "\"${graphHopperApiKey}\"")
    }

    signingConfigs {
        // Only declare a real release config when a keystore is actually provided,
        // so a plain checkout still builds (falling back to debug signing below).
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate + strip unused resources for the shipped build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real keystore when configured; else the debug key so a local
            // `assembleRelease` still yields an installable APK for smoke-testing.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.service)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}