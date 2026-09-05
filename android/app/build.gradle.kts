plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.watchbridge"
    compileSdk = 36

    defaultConfig {
        // Fresh package ID prevents Android from treating this Chairman build
        // as an update to older test APKs signed by different ephemeral keys.
        applicationId = "com.jarvis.chairman"
        minSdk = 28
        targetSdk = 35
        versionCode = 220
        versionName = "2.2.0"

        val apiBaseUrl = System.getenv("JARVIS_API_BASE_URL")
            ?.takeIf { it.isNotBlank() }
            ?: "https://jarvis-watch-bridge-api.onrender.com/"
        val setupToken = System.getenv("JARVIS_SETUP_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?: ""

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "JARVIS_SETUP_TOKEN", "\"$setupToken\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
