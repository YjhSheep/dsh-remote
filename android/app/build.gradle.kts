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
        versionCode = 13
        versionName = "0.13"
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
    // Only for ViewCompat/WindowInsetsCompat in WebActivity.applyImmersive: the platform insets
    // API it needs is API 30+, and this app also runs on API 26. Nothing else needs a library -
    // there is no okhttp (the WebView speaks HTTP, not the app), no AppCompat (the shell is a plain
    // android.app.Activity) and no Material (its Toolbar/ProgressBar/AlertDialog are platform ones).
    implementation("androidx.core:core:1.13.1")
}
