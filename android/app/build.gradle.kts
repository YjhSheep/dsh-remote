plugins {
    id("com.android.application")
}

android {
    namespace = "app.dsh.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.dsh.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Same keystore the hand-rolled pipeline uses (android/dsh-debug.keystore).
    signingConfigs {
        create("dsh") {
            storeFile = file("../dsh-debug.keystore")
            storePassword = "android"
            keyAlias = "dsh"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            // AGP's default debug keystore lives in C:\Users\<user>\.android, which is refused for
            // the Gradle daemon on this box ("AccessDeniedException: debug.keystore.lock"); the
            // project already ships its own keystore, so both build types use it.
            signingConfig = signingConfigs.getByName("dsh")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dsh")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // WebSocket client for /api/remote.mux (and TLS termination when reached through a tunnel).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}