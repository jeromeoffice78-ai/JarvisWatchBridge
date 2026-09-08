plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.watchbridge"
    compileSdk = 36

    defaultConfig {
        // Separate Chairman Access package installs alongside every earlier JARVIS build.
        applicationId = "chairman.access.android.smartwatch.bridge"
        minSdk = 28
        targetSdk = 36
        versionCode = 320
        versionName = "3.2.0"

        // Render-only FastAPI backend. No CloudFront/Floot fallback exists in this build.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://jarvis-watch-bridge-api.onrender.com/\""
        )

        val setupToken = System.getenv("JARVIS_SETUP_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?: ""
        buildConfigField("String", "JARVIS_SETUP_TOKEN", "\"$setupToken\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
