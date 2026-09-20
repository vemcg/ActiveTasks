import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildInstant = Instant.now()
val defaultVersionCode = buildInstant.epochSecond.toInt()

// Base app version. Bump manually for meaningful releases.
val versionBase = providers.gradleProperty("buildVersionBase").orElse("0.1.0").get()
// Unpadded build/run number (e.g. CI run number). Kept numeric so "10" never sorts
// before "2" the way it would under plain lexical string comparison.
val buildNumber = providers.gradleProperty("buildNumber").orElse("0").get()
val buildTimestamp = providers.gradleProperty("buildTimestamp").orElse(
    DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC).format(buildInstant)
).get()
fun currentGitShortSha(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifBlank { "unknown" }
} catch (e: Exception) {
    "unknown"
}

val gitShortSha = providers.gradleProperty("buildGitSha").orElse(currentGitShortSha()).get()

fun currentGitBranch(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifBlank { "unknown" }
} catch (e: Exception) {
    "unknown"
}

val gitBranch = providers.gradleProperty("buildGitBranch").orElse(currentGitBranch()).get()

// Short, human-facing version: base + unpadded build number only (e.g. "0.1.0-11").
// Full provenance (timestamp, commit) is exposed separately via BuildConfig for display
// in Settings, rather than being embedded in versionName.
val shortVersionName = "$versionBase-$buildNumber"
val fullVersionLabel = "v$shortVersionName-$buildTimestamp-$gitShortSha-$gitBranch"

android {
    namespace = "com.activetasks.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.activetasks.app"
        minSdk = 26
        targetSdk = 34
        // Must stay a single monotonically increasing integer (Android requirement for
        // update-safety) - never derived from lexical comparison of the display string.
        versionCode = providers.gradleProperty("buildVersionCode").map(String::toInt).orElse(defaultVersionCode).get()
        versionName = providers.gradleProperty("buildVersionName").orElse(shortVersionName).get()

        buildConfigField("String", "VERSION_BASE", "\"$versionBase\"")
        buildConfigField("int", "BUILD_NUMBER", buildNumber)
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "FULL_VERSION_LABEL", "\"$fullVersionLabel\"")
    }

    signingConfigs {
        getByName("debug") {
            // Checked-in debug key (not a secret by design) keeps CI/local builds
            // consistently signed so sideloaded updates install over prior versions.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
