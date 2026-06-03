plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cz.naviglink.driver"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.naviglink.driver"
        minSdk = 29              // Android 10 — pokrývá ~99 % zařízení v ČR
        targetSdk = 34           // Android 14
        versionCode = 1
        versionName = "0.1.0"

        // MapTiler API key (free tier, ~100k tile fetches/měsíc).
        // Pro pilot s pár drivery bohatě stačí. Při bližení produkce přesunout
        // do gradle.properties + .gitignore, nebo CI secret.
        buildConfigField("String", "MAPTILER_KEY", "\"APR27qjEaguTJBTnt8X5\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false   // pro MVP držíme bez ProGuard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // === Compose ===
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // === Location ===
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // === HTTP klient (Ktor) ===
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // === Serialization ===
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // === Coroutines ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // === Crypto: Ed25519 přes Bouncy Castle ===
    // Bouncy Castle pro Ed25519 — Android Keystore má native Ed25519 až od API 33,
    // pro pilot držíme jednotný stack (software Ed25519, klíče v EncryptedSharedPreferences).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // === BIP39 mnemonic pro backup klíčů ===
    // 12-slovní fráze → deterministické odvození Ed25519 klíče. Identický postup
    // jako v admin webu (@scure/bip39), aby stejná fráze fungovala napříč platformami.
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.8")

    // === EncryptedSharedPreferences ===
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // === WorkManager (periodic polling /alerts) ===
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // === Mapa: MapLibre GL Native (OSS fork Mapbox GL pre-licence change) ===
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // === Testing ===
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
