import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint")
}

val versionProperties =
    Properties().apply {
        rootProject.file("version.properties").inputStream().use { load(it) }
    }
val appVersionName = versionProperties.getProperty("VERSION_NAME")
val appVersionCode = versionProperties.getProperty("VERSION_CODE").toInt()

// Give each ABI split a distinct versionCode so the variants never look like
// the same update to an installer. The universal APK keeps the base code.
val abiVersionCodeOffsets =
    mapOf(
        "arm64-v8a" to 1,
        "armeabi-v7a" to 2,
        "x86_64" to 3,
    )

android {
    namespace = "io.github.aurelia.nlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aurelia.nlauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    dependenciesInfo {
        // Google's dependency-info block is a non-free, non-reproducible blob.
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            // No keystore in a clean checkout: F-Droid builds unsigned and signs
            // with its own key. Signing is enabled only when the release CI
            // supplies a keystore through the environment.
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias =
                    System
                        .getenv("ANDROID_KEY_ALIAS")
                        ?.takeIf { it.isNotBlank() }
                        ?: "release"
                keyPassword =
                    System
                        .getenv("ANDROID_KEY_PASSWORD")
                        ?.takeIf { it.isNotBlank() }
                        ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // This embeds the absolute checkout path, which differs between
            // GitHub Actions and F-Droid's build server.
            vcsInfo {
                include = false
            }
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
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi =
                output.filters
                    .find { it.filterType.toString() == "ABI" }
                    ?.identifier
            val code =
                abi?.let { appVersionCode + abiVersionCodeOffsets.getValue(it) }
                    ?: appVersionCode
            val suffix = abi ?: "universal"
            output.versionCode.set(code)
            output.outputFileName.set(
                if (variant.name == "release") {
                    "nLauncher-$appVersionName-$code-$suffix.apk"
                } else {
                    "nLauncher-$appVersionName-$code-$suffix-${variant.name}.apk"
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
