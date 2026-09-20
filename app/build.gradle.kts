plugins {
    id("com.android.application")
}

android {
    namespace = "com.espclaw.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.espclaw.mobile"
        minSdk = 29
        // Kept below 35 so the soft keyboard resizes the WebView (no forced edge-to-edge).
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
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
}
