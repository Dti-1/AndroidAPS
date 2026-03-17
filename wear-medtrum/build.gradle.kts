// build.gradle.kts — Module wear-medtrum
//
// Dépendances minimales pour faire tourner le driver Medtrum sur Wear OS
// sans embarquer tout le framework AAPS.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")         // Room code generation
    id("dagger.hilt.android.plugin")      // DI (optionnel, peut être remplacé par manuel)
}

android {
    namespace   = "app.aaps.wear.medtrum"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "app.aaps.wear.medtrum"
        minSdk          = 30       // Wear OS 3.0 minimum (Android 11)
        targetSdk       = 35
        versionCode     = 1
        versionName     = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Optimisations importantes pour Wear OS (mémoire limitée)
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}

dependencies {

    // ── Wear OS ──────────────────────────────────────────────────────
    implementation("androidx.wear:wear:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    // ── Jetpack Compose for Wear OS ───────────────────────────────────
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.wear.compose:compose-navigation:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // ── Kotlin Coroutines ─────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // ── Room DB (base locale Wear OS) ─────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── DataStore (remplace SharedPreferences sur Wear OS) ────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Lifecycle ─────────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-service:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    // ── Core AAPS interfaces (depuis le projet parent) ─────────────────
    // Ces modules contiennent les interfaces PumpSync, MedtrumPump, etc.
    // À ajuster selon la structure exacte du fork Dti-1
    implementation(project(":core:interfaces"))
    implementation(project(":core:data"))
    implementation(project(":core:keys"))
    implementation(project(":pump:medtrum"))   // Pour réutiliser MedtrumPump.kt

    // ── Hilt DI (facultatif) ──────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-compiler:2.51")
}
