plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    id("org.jetbrains.kotlin.kapt")
}

/*
 * The fixed key every CI build is signed with (GitHub secrets, decoded by
 * .github/workflows/build.yml). An APK signed with the same key as the one
 * installed installs over it and keeps the phone's cards, faces and unsent
 * scans; a different key forces an uninstall, which wipes them. The key is
 * never in this repo (it's public). Without it - a local build, a fork - the
 * build falls back to the debug key, and CI labels that APK as not for gates.
 */
val gateKeystore: File? = System.getenv("GATE_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.muslimedu.attendance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.muslimedu.attendance"
        minSdk = 26
        targetSdk = 34
        // CI's run number, so the web's Gate Devices page shows which build each gate phone runs.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciBuild ?: 1
        versionName = if (ciBuild != null) "0.1.$ciBuild" else "0.1.0-local"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "API_BASE_URL", "\"https://manhaje.com/apps/api/\"")
    }

    signingConfigs {
        if (gateKeystore != null) {
            create("gate") {
                storeFile = gateKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("GATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GATE_KEY_ALIAS") ?: "gate"
                // PKCS12: the key's password is the store's.
                keyPassword = System.getenv("GATE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // Both APKs get the gate key, so installing either never forces an uninstall.
        debug {
            if (gateKeystore != null) signingConfig = signingConfigs.getByName("gate")
        }
        release {
            isMinifyEnabled = false
            // Sideloaded, not on a store. The release APK is the one for the
            // gate phones - debug builds are debuggable, which makes Compose
            // noticeably laggy on budget phones.
            signingConfig = signingConfigs.getByName(if (gateKeystore != null) "gate" else "debug")
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

    lint {
        abortOnError = false
        htmlReport = true
    }

    // The face model is memory-mapped from the APK (MobileFaceNetRecognizer),
    // which only works on an uncompressed asset.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    kapt(libs.androidx.hilt.compiler)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Gson
    implementation(libs.gson)

    // ML Kit Face Detection
    implementation(libs.mlkit.face.detection)

    // CameraX - embedded live camera preview for face capture (LiveFaceCaptureView)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TensorFlow Lite
    implementation(libs.tensorflow.lite)

    // Security & Tink
    implementation(libs.tink.android)
    implementation(libs.androidx.security.crypto)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}
