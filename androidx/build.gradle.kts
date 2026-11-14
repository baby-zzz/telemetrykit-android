import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

// The opt-in Android companion: lifecycle wiring, screen views, process-exit reasons and privacy-safe tap tracking.
// The core (the root project) stays free of the Android SDK; only this module needs it.
android {
    namespace = "app.babyzzz.telemetrykit.androidx"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("robolectric.logging", "stdout")
                providers.gradleProperty("robolectricRepo").orNull?.let { repo -> it.systemProperty("maven.repo.local", repo) }
            }
        }
    }

    lint {
        xmlReport = true
        htmlReport = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    api(project(":"))
    // Compose is compileOnly: not in the POM. Tap tracking runs only when the app has Compose (ComposeSupport); pinned
    // to what the Baby Zzz app's Compose BOM resolves
    compileOnly(libs.compose.ui)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    // CI agent build-1 may be Linux aarch64: Robolectric's native runtime for it (as the Baby Zzz app's test setup)
    testRuntimeOnly(libs.robolectric.nativeruntime.arm64)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    constraints {
        testImplementation("com.google.guava:guava:${libs.versions.guava.get()}-jre") { because("Robolectric brings 33.4.8-jre: CVE-2026-102554") }
        testImplementation(libs.bouncycastle.bcprov) { because("Robolectric 4.16.1 asks for 1.81: CVE-2025-14813, CVE-2026-8763") }
        testImplementation(libs.conscrypt.openjdk.uber) { because("Robolectric 4.16.1 asks for 2.5.2 which lacks linux-aarch_64 native binaries") }
    }
}

// Maven Central like the core: sources jar, a (Kotlin) javadoc jar, signed POM, signed only when the release job gives the key
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "telemetrykit-androidx", version.toString())
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    pom {
        name.set("TelemetryKit for Android (AndroidX)")
        description.set("Opt-in Android companion for TelemetryKit: lifecycle wiring, screen views, process-exit reasons and privacy-safe tap tracking")
        inceptionYear.set("2026")
        url.set("https://github.com/baby-zzz/telemetrykit-android")
        licenses {
            license {
                name.set("ISC License")
                url.set("https://opensource.org/license/isc-license-txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("baby-zzz")
                name.set("Baby Zzz's LLC")
                url.set("https://github.com/baby-zzz")
            }
        }
        scm {
            url.set("https://github.com/baby-zzz/telemetrykit-android")
            connection.set("scm:git:git://github.com/baby-zzz/telemetrykit-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/baby-zzz/telemetrykit-android.git")
        }
    }
}

// The companion must not bring Compose (or anything else) into an app's graph: the POM lists the core, lifecycle-process and the Kotlin stdlib only
val verifyPom by tasks.registering {
    val pom = layout.buildDirectory.file("publications/maven/pom-default.xml")
    dependsOn("generatePomFileForMavenPublication")
    inputs.file(pom)
    doLast {
        val text = pom.get().asFile.readText()
        val artifacts = Regex("<artifactId>([^<]+)</artifactId>").findAll(text.substringAfter("<dependencies>").substringBefore("</dependencies>")).map { it.groupValues[1] }.toList()
        check(artifacts.sorted() == listOf("kotlin-stdlib", "lifecycle-process", "telemetrykit")) { "Unexpected POM dependencies: $artifacts" }
        check("compose" !in text.substringAfter("<dependencies>")) { "Compose must not be in the POM" }
    }
}
tasks.named("check") { dependsOn(verifyPom) }
