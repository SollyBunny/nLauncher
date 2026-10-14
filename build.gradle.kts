// Top-level build file. Plugin versions are declared here and applied in :app.
//
// Kotlin is not applied as a plugin: AGP's built-in Kotlin support does that, using the
// Kotlin Gradle plugin version AGP ships with.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}