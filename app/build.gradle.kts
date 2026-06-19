plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.topsearch.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.topsearch.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = file("topsearch-release.jks")
            storePassword = "topsearch123"
            keyAlias      = "topsearch"
            keyPassword   = "topsearch123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // ML Kit Text Recognition (on-device, Latin script)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // WebKit (ProxyController, ProxyConfig)
    implementation("androidx.webkit:webkit:1.12.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebSocket (dùng cho SignalR client)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
