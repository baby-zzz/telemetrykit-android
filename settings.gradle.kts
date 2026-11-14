rootProject.name = "telemetrykit-android"

pluginManagement {
    repositories {
        // The Android Gradle plugin (and nothing else) comes from Google
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
    }
}

// The Android companion (telemetrykit-androidx); the root project stays the plain JVM core
include(":androidx")
