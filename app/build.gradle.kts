plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.classfruit.hotpoint"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.classfruit.hotpoint"
        minSdk = 24
        // 28 keeps background FGS starts and exec-from-data-dir rules relaxed; device is API 30.
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // sideloaded on one watch; debug key keeps `adb install -r` upgrades working
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }
}