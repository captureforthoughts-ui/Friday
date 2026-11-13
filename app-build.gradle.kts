plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.friday.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.friday.phone"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "3.8"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("friday-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
