plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.watchbridge"
    compileSdk = 36

    defaultConfig {
        // Existing production package remains unchanged.
        applicationId = "com.jarvis.chairman.render255"
        minSdk = 28
        targetSdk = 36
        versionCode = 256
        versionName = "2.5.6"

        // Render FastAPI backend.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://jarvis-watch-bridge-api.onrender.com/\""
        )

        // Legacy builds may still receive this at build time. The dedicated
        // Chairman Companion build explicitly clears it so no admin secret is
        // embedded in that APK.
        val setupToken = System.getenv("JARVIS_SETUP_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?: ""
        buildConfigField("String", "JARVIS_SETUP_TOKEN", "\"$setupToken\"")
        buildConfigField("String", "ACCESS_MODE", "\"STANDARD\"")
        resValue("string", "app_name", "JARVIS Watch Bridge")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }

        // Separate installable companion for the Chairman. It reuses the
        // stable watch/voice/health code but gets a unique application ID.
        create("chairmanCompanion") {
            initWith(getByName("release"))
            applicationIdSuffix = ".companion"
            versionNameSuffix = "-chairman-companion"
            isDebuggable = false
            matchingFallbacks += listOf("release")
            buildConfigField("String", "ACCESS_MODE", "\"CHAIRMAN\"")
            buildConfigField("String", "JARVIS_SETUP_TOKEN", "\"\"")
            resValue("string", "app_name", "JARVIS Chairman Companion")
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
