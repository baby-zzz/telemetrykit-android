# TelemetryKit for Android

A minimal client for the [Grafana Faro](https://grafana.com/oss/faro/) receiver, for Kotlin/JVM and Android apps. It
posts events, logs, exceptions and measurements in the Faro web SDK's JSON format to a receiver such as Grafana
Alloy's `faro.receiver`. There is no OpenTelemetry, no tracing and no instrumentation: your code records what
happened, the library queues, scrubs, batches and sends it.

This is an independent project. It is not affiliated with, endorsed by or sponsored by Grafana Labs; "Grafana" and
"Faro" are used only to describe what the library talks to.

- A plain Kotlin/JVM library: it does not need the Android SDK to build or test, and has no Android dependencies.
- Dependencies: kotlinx.coroutines, kotlinx.serialization and OkHttp (the transport only).
- Every call on the `Telemetry` facade is a cheap enqueue that never throws.
- Nothing is sent without consent, a "build allows" gate and an enabled remote flag.

## Installation

From Maven Central:

<!-- x-release-please-start-version -->
```kotlin
// build.gradle.kts
dependencies {
    implementation("app.babyzzz.telemetrykit:telemetrykit:0.2.0")
}
```
<!-- x-release-please-end -->

Until it is on a public repository, publish it locally with `./gradlew publishToMavenLocal` and add `mavenLocal()`.
Requires JVM 17 bytecode (any Android app on AGP 8) and `java.time`: Android API 26+, or core library desugaring below that.

### Using an unreleased build

To try changes in an app before they are released to Maven Central, either:

- **Composite build (recommended while developing):** point the app's `settings.gradle.kts` at a checkout; Gradle builds
  the library from source and uses it in place of the published artifact, so edits show up on the next app build:

  ```kotlin
  // settings.gradle.kts of the app
  includeBuild("../telemetrykit-android") {
      dependencySubstitution {
          substitute(module("app.babyzzz.telemetrykit:telemetrykit")).using(project(":"))
      }
  }
  ```

- **Maven local:** install a build into `~/.m2` under a version of its own, and depend on that version with
  `mavenLocal()` in the app's repositories:

  ```sh
  ./gradlew publishToMavenLocal -Pversion=0.2.0-local
  ```

  Local builds are not signed (only the release job has the signing key).

## Quick start

```kotlin
val configuration = Configuration(
    endpoint = "https://collector.example.com/collect",
    app = FaroMeta.App(
        name = "my-app", namespace = "android", version = "1.4.0+42",
        environment = "production", bundleId = "com.example.app",
    ),
    browser = FaroMeta.Browser(
        name = "MyApp-Android", version = "1.4.0", os = "Android ${Build.VERSION.RELEASE}",
        mobile = true, language = Locale.getDefault().toLanguageTag(),
    ),
    platform = "android",          // session_attr_platform
    domain = "android",            // domain of every event
    consentKey = "telemetry_consent",
    sessionKey = "telemetry_session",
)

val started = Telemetry.start(
    configuration = configuration,
    store = MyStore(prefs),                          // a TelemetryStore, e.g. over SharedPreferences
    storeFile = File(cacheDir, "telemetry/pending.json"),
    debug = BuildConfig.DEBUG,
    debugSwitch = false,                              // true: a debug build may send
)

// The host decides when the remote flag is known: null = unknown (queued in memory), false = drop, true = send
Telemetry.setRemoteEnabled(true)
Telemetry.setUser(userId)                             // an opaque id, never an e-mail or a name

Telemetry.event("checkout_started", mapOf("step" to "payment"))
Telemetry.error(exception, where = "checkout")
Telemetry.log(FaroLog.Level.WARN, category = "auth", message = "Token refresh refused")
```

`Configuration` has no defaults for anything that identifies your app, collector or store. The tuning values have
defaults: `flushDelayMillis` 5 s, `maxBatch` 50, `maxPending` 500, `maxStoredBytes` 512 KiB, `maxStoredAgeMillis` 24 h,
`backoffMillis` 5 s / 30 s / 2 min / 10 min, `sessionInactivityMillis` 15 min, `maxErrorValueLength` 500. The SDK name
and version default to this library (`telemetrykit-android`, its version).

### Store

```kotlin
class PrefsStore(private val prefs: SharedPreferences) : TelemetryStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}
```

`InMemoryTelemetryStore` is there for tests.

### Consent

```kotlin
Telemetry.setConsent(false)   // drops the queue and the pending file, stores the choice under consentKey
```

A missing stored value means `consentDefault`: false (opt-in) by default; set it to true for an opt-out design, and
disclose that in your privacy policy.

## Lifecycle wiring

The library does not depend on AndroidX. Call `onStop()` and `onStart()` from the process lifecycle: on stop the queue
is written to the pending file and then sent (Android gives no grace period); on start the file is removed because the
queue is still in memory.

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> Telemetry.onStop()
            Lifecycle.Event.ON_START -> Telemetry.onStart()
            else -> Unit
        }
    },
)
```

(`androidx.lifecycle:lifecycle-process` in your app.)

## Errors and R8 / ProGuard

`Telemetry.error` sends the exception's class simple name as `type` and, as `value`, `errorKind(error)` followed by the
message, scrubbed and cut at `maxErrorValueLength`. No stack frames are sent. Pass an `errorKind` hook in
`Configuration` to group by something else (an HTTP status, a domain code). Because the default is the class name, keep
exception names readable in minified builds:

```
-keepnames class * extends java.lang.Throwable
```

This is a plain JVM jar, so it ships no consumer rules; add the line to your app's `proguard-rules.pro`. The library's
serialization models use explicit `@SerialName`s and kotlinx.serialization's own consumer rules, which R8 picks up
from the dependency. OkHttp's rules ship with OkHttp.

## Configurable scrubbing

By default every free string is scrubbed. `Configuration.scrubPolicy` chooses which, per field; `ScrubPolicy.ALL`
(the default) scrubs everything and `ScrubPolicy.NONE` nothing:

| `ScrubPolicy` field    | Covers                                   |
| ---------------------- | ---------------------------------------- |
| `eventAttributes`      | event attribute values                   |
| `logMessages`          | log messages                             |
| `logContext`           | log context values (not `category`)      |
| `exceptionValues`      | the error `value` (kind and message)     |
| `exceptionContext`     | exception context values (not `where`)   |
| `measurementContext`   | measurement context values               |

Every recording call (`event`, `eventThrottled`, `error`, `log`, `measurement`, and `TelemetryClient.record` /
`recordThrottled`) takes a last optional `scrub: Boolean? = null`: `null` follows the policy, `true` scrubs every free
string of that item, `false` scrubs none of them. The per-call value always wins over the policy.

```kotlin
val configuration = Configuration(
    /* ... */
    scrubPolicy = ScrubPolicy.ALL.copy(logMessages = false), // my log messages are constants
    extraScrubParameters = listOf("invite"),                 // still customises the scrubber when it runs
)
Telemetry.error(error, where = "checkout", scrub = true)       // scrubbed whatever the policy says
Telemetry.event("tapped", mapOf("screen" to "home"), scrub = false)
```

Scrubbing still fails closed whenever it runs. Turning it off is the host app's responsibility.

## Privacy notes

- Free strings (event attributes, log messages and context, exception value and context, measurement context) pass
  `TelemetryScrub` before they are queued, unless you turn that off (see "Configurable scrubbing" below). Names, types,
  levels, `where`, `category`, timestamps and meta are never scrubbed: they must be constants.
- Default scrub: secret query parameters (`signed`, `ts`, `token`, `access_token`, `refresh_token`, `id_token`, `code`,
  `state`, `session_state`, `key`, `password`, `secret`), e-mail addresses (letters-only TLD, so `sdk@1.2.3` stays),
  `Bearer` tokens and `scheme://user:password@`. If a pattern cannot be built or throws, the text is replaced
  entirely (fail closed).
- With scrubbing off, values are sent as given: what reaches the collector is then the host app's responsibility.
- Add your own with `extraScrubParameters` (literal names) and `extraScrubPatterns` (the whole match is replaced).
- The user is whatever id you pass to `setUser`; the library never reads one.
- The transport uses its own OkHttp client: no certificate pinning, interceptors, cookies, cache or redirects, so a
  host's pin failure is never reported through telemetry and the host's interceptors never see these requests.
- The pending file holds queued items (already scrubbed) for at most `maxStoredAgeMillis`; it is deleted when consent
  turns off or the remote flag says disabled.
- Scrub again on the server (for example Alloy `loki.process`); defence in depth.

## Alloy receiver

```alloy
faro.receiver "app" {
  server {
    listen_address = "0.0.0.0"
    listen_port    = 12347
    cors_allowed_origins = ["*"]   // native apps send no Origin; restrict if browsers use the same receiver
  }

  output {
    logs   = [loki.process.faro_scrub.receiver]
    traces = []
  }
}

loki.process "faro_scrub" {
  stage.replace {
    expression = "(?i)([?&](?:token|code|state|signed|password|secret)=)[^&#\\s\"']*"
    replace    = "${1}[REDACTED]"
  }
  forward_to = [loki.write.default.receiver]
}
```

Put the receiver behind HTTPS and point `Configuration.endpoint` at its `/collect` path. Events, logs, exceptions and
measurements become Loki lines with the `meta` fields as keys (`app_name`, `session_id`, `session_attr_platform`,
`user_id`, `browser_os`, `view_name`, ...).

## Transport

`TelemetryTransport` is a `fun interface`; pass your own to `Telemetry.start(transport = ...)` or to `TelemetryClient`.
Status 2xx is success, 408/429/5xx are retried with backoff (honouring `Retry-After` in seconds), other statuses drop
the batch, and an exception keeps it.

## Build

```
./gradlew build
./gradlew publishToMavenLocal
```

## License

ISC License, copyright 2026 Baby Zzz's LLC. See [LICENSE](LICENSE).
