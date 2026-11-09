import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

// A plain Kotlin/JVM library: nothing here needs the Android SDK (OkHttp, kotlinx and java.io only), and it runs on
// Android (JVM 17 bytecode; java.time needs API 26+ or core library desugaring).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "telemetrykit"
            pom {
                name.set("telemetrykit")
                description.set("A minimal Grafana Faro receiver client for Kotlin/JVM and Android, without OpenTelemetry")
                licenses {
                    license {
                        name.set("ISC License")
                        url.set("https://opensource.org/license/isc-license-txt")
                    }
                }
            }
        }
    }
}
