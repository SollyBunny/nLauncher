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

    signingConfigs {
        create("release") {
            storeFile =
                rootProject.file(
                    System
                        .getenv("ANDROID_KEYSTORE_PATH")
                        ?.takeIf { it.isNotBlank() }
                        ?: "release.jks",
                )
            storePassword =
                System
                    .getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
            keyAlias = System
                .getenv("ANDROID_KEY_ALIAS")
                ?.takeIf { it.isNotBlank() }
                ?: "release"
            keyPassword = System
                .getenv("ANDROID_KEY_PASSWORD")
                ?.takeIf { it.isNotBlank() }
                ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        disable += "InflateParams"
        disable += "Overdraw"
    }

    splits {
        abi {
            isEnable = true
            reset()
            val arm64Only =
                providers
                    .gradleProperty("arm64Only")
                    .map { it.toBoolean() }
                    .getOrElse(false)
            if (arm64Only) {
                include("arm64-v8a")
                isUniversalApk = false
            } else {
                include("arm64-v8a", "armeabi-v7a", "x86_64")
                isUniversalApk = true
            }
        }
    }
}

androidComponents {
    val version = android.defaultConfig.versionName
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi =
                output.filters
                    .find { it.filterType.toString() == "ABI" }
                    ?.identifier
                    ?: "universal"
            output.outputFileName.set(
                if (variant.name == "release") {
                    "nLauncher-$version-$abi.apk"
                } else {
                    "nLauncher-$version-$abi-${variant.name}.apk"
                },
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
