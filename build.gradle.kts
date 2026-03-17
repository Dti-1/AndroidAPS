// build.gradle.kts — Module wear-medtrum (Standalone Wear OS)
//
// PRÉREQUIS dans gradle/libs.versions.toml :
//
// [versions]
//   wearCompose = "1.5.6"
//
// [plugins]
//   android-application = { id = "com.android.application", version.ref = "gradlePlugin" }
//
// [libraries]
//   androidx-wear-compose-material   = { group = "androidx.wear.compose", name = "compose-material",   version.ref = "wearCompose" }
//   androidx-wear-compose-foundation = { group = "androidx.wear.compose", name = "compose-foundation", version.ref = "wearCompose" }
//   androidx-wear-compose-navigation = { group = "androidx.wear.compose", name = "compose-navigation", version.ref = "wearCompose" }

plugins {
    alias(libs.plugins.android.application)  // nécessite l'ajout dans libs.versions.toml
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace  = "app.aaps.wear.medtrum"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.aaps.wear.medtrum"
        minSdk        = 30
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    // ── Wear OS ──────────────────────────────────────────────────────
    implementation(libs.androidx.wear)
    implementation(libs.com.google.android.gms.playservices.wearable)
    compileOnly(libs.com.google.android.wearable)
    implementation(libs.com.google.android.wearable.support)

    // ── Compose BOM ──────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)

    // ── Wear Compose (ajoutés dans libs.versions.toml) ───────────────
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // ── Coroutines ───────────────────────────────────────────────────
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(libs.kotlinx.coroutines.android)

    // ── Room ─────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room)
    ksp(libs.androidx.room.compiler)

    // ── DataStore ────────────────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── Modules AAPS ─────────────────────────────────────────────────
    implementation(project(":core:interfaces"))
    implementation(project(":core:data"))
    implementation(project(":core:keys"))
    implementation(project(":pump:medtrum"))
}
