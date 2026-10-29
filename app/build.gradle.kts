plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.aurelia.nlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aurelia.nlauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Every screen inflates its own root with a null parent on purpose: the
        // UiStack attaches it and supplies the LayoutParams, and inflating with a
        // parent here would apply the wrong ones.
        disable += "InflateParams"
        // Rows paint a ripple over a background that is either transparent (the
        // wallpaper shows through) or already covered by an opaque screen.
        disable += "Overdraw"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
