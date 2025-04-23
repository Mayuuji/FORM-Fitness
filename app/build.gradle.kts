plugins {
    // Android application plugin
    id("com.android.application")
    // Kotlin Android support
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hfad.camera2"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.hfad.camera2"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // disable code shrinking for now
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // use Java 8 APIs
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        // match Java 8 target
        jvmTarget = "1.8"
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Components
    implementation("com.google.android.material:material:1.9.0")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")

    // Coroutines for your MainScope / launch { … }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // TensorFlow Lite & GPU delegate
    implementation("org.tensorflow:tensorflow-lite:2.9.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")
    implementation ("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Android instrumentation tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
