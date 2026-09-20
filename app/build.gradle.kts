import java.util.Properties

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

    // Release signing: read from keystore.properties (git-ignored) so secrets never enter the repo.
    // Without that file, `assembleRelease` yields an unsigned APK and `assembleDebug` is unaffected.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
