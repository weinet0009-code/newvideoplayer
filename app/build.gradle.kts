plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.weinet.newvideoplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.weinet.newvideoplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // OpenSubtitles consumer key (one per app). Set it as a Gradle property or an environment variable.
        val osKey = providers.gradleProperty("OPENSUBTITLES_API_KEY").orNull
            ?: System.getenv("OPENSUBTITLES_API_KEY") ?: ""
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$osKey\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // whisper.cpp (offline AI subtitles), fetched and built from source by CMake.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // ggml loads its CPU variant (libggml-cpu-*.so) with dlopen from nativeLibraryDir,
    // so native libs must be extracted to disk instead of mapped from the APK.
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.cast)
    implementation(libs.media3.effect)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    testImplementation(libs.junit)
}
