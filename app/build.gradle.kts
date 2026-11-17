plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gwgm.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gwgm.manager"
        // Android 11+ is required on the PHONE for TLS wireless-debugging pairing.
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0-games"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloading only: release is signed with the debug key so it installs
            // without extra setup. Create your own keystore before distributing to anyone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", // BouncyCastle duplicate
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Wireless ADB protocol (pairing w/ 6-digit code, TLS, separate pairing/connect ports).
    // https://github.com/MuntashirAkon/libadb-android (via JitPack)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Required by libadb for TLS pairing (exportKeyingMaterial).
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed certificate generation for this phone's ADB identity.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81") // matches the bcprov libadb pulls in

    testImplementation("junit:junit:4.13.2")
}
