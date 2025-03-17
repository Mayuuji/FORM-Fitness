plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hfad.camera2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hfad.camera2"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    androidResources {
        noCompress += "tflite"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    //implementation("androidx.camera:camera-core:1.3.0")
    //implementation("androidx.camera:camera-camera2:1.3.0")
    //implementation("androidx.camera:camera-lifecycle:1.3.0")
    //implementation("androidx.camera:camera-view:1.3.0")
    //implementation("androidx.camera:camera-extensions:1.3.0") // Extensions API
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.10.1")

    implementation("org.tensorflow:tensorflow-lite:0.4.2")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.2")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.2")

    // Ensure CameraX dependencies are included
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    //implementation ("org.tensorflow:tensorflow-lite:2.12.0") {
      //  exclude(group = "org.tensorflow", module = "tensorflow-lite-task-vision")
    //}
    //implementation ("org.tensorflow:tensorflow-lite-task-vision:2.12.0")
    //implementation("org.tensorflow:tensorflow-lite-support:2.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}