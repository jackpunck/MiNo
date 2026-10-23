plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Desktop version is the default; Android release metadata supports independent versions.
val desktopVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
    .find(rootProject.file("../src-tauri/tauri.conf.json").readText())?.groupValues?.get(1)
    ?: error("Missing app version")
val appVersion = System.getenv("ANDROID_VERSION_NAME") ?: desktopVersion
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(appVersion)) {
    "ANDROID_VERSION_NAME must be a canonical major.minor.patch version"
}
val parts = appVersion.split(".").map { it.toLongOrNull() ?: error("Invalid version component") }
require(parts[0] <= 210000 && parts[1] <= 99 && parts[2] <= 99) { "Version components out of range" }
val codeOverride = System.getenv("ANDROID_VERSION_CODE")
require(codeOverride == null || Regex("[1-9][0-9]*").matches(codeOverride)) { "Invalid ANDROID_VERSION_CODE" }
val androidCode = if (codeOverride == null) parts[0] * 10000 + parts[1] * 100 + parts[2]
    else codeOverride.toLongOrNull() ?: error("Invalid ANDROID_VERSION_CODE")
require(androidCode in 1L..2100000000L) { "ANDROID_VERSION_CODE must be in 1..2100000000" }

val signingKeys = listOf("MINO_KEYSTORE_PATH", "MINO_KEYSTORE_PASSWORD", "MINO_KEY_ALIAS", "MINO_KEY_PASSWORD")
val signingValues = signingKeys.associateWith { System.getenv(it) }
val hasReleaseSigning = signingValues.values.all { !it.isNullOrBlank() }
// Validate only when a release task is in the actual graph; PR benchmark needs no secrets.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name.contains("Release", ignoreCase = true) }) {
        check(hasReleaseSigning) {
            "Release signing requires: " + signingKeys.filter { signingValues[it].isNullOrBlank() }.joinToString(", ")
        }
        check(file(signingValues.getValue("MINO_KEYSTORE_PATH")!!).isFile) { "MINO_KEYSTORE_PATH must be an existing keystore file" }
    }
}

android {
    namespace = "com.mino.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mino.app"
        minSdk = 26
        targetSdk = 35
        versionCode = androidCode.toInt()
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingValues.getValue("MINO_KEYSTORE_PATH")!!)
                storePassword = signingValues.getValue("MINO_KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("MINO_KEY_ALIAS")
                keyPassword = signingValues.getValue("MINO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // Small installable acceptance build. Local debug signing is not a release key.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // Isolated package for process-death tests; never reads the user's task file.
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".probe"
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
