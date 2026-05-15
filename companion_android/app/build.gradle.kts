plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.carcompanion.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carcompanion.companion"
        minSdk = 31
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"

        // GitHub release coordinates for the assets-pack downloader.
        // - REPO_OWNER / REPO_NAME locate the public release feed
        // - MIN_ASSETS_VERSION is the minimum version this APK can work
        //   with; a fresh install whose installed manifest < MIN must
        //   trigger a download before the soul pipeline broadcasts
        //   anything audible.
        buildConfigField("String", "REPO_OWNER", "\"comdet\"")
        buildConfigField("String", "REPO_NAME", "\"KuroCompanionApp\"")
        buildConfigField("String", "MIN_ASSETS_VERSION", "\"v1.0.0\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Charts for SoulDebugActivity (Vico — Compose Material 3 module)
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-alpha.28")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit-test stack — JUnit 4 (Android default) + coroutines-test so
    // pure-logic classes (RobotPresence, TripPhaseAnalyzer, ReactionPolicy,
    // EpisodeTracker, …) can be exercised without an emulator.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
