import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

// A plain Kotlin/JVM library: nothing here needs the Android SDK (OkHttp, kotlinx and java.io only), and it runs on
// Android (JVM 17 bytecode; java.time needs API 26+ or core library desugaring).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    // In the public API: CoroutineScope, Json, the @Serializable payload types
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Not in the public API: the transport keeps its own client
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    systemProperty("telemetrykit.version", project.version.toString())
}

// Maven Central (Central Portal) through the vanniktech plugin: sources jar, an empty javadoc jar (Kotlin), signed
// POM. Credentials and the signing key come from ORG_GRADLE_PROJECT_* variables in the release workflow.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "telemetrykit", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    pom {
        name.set("TelemetryKit for Android")
        description.set("A minimal Grafana Faro receiver client for Kotlin/JVM and Android, without OpenTelemetry")
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
