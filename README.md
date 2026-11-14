# TelemetryKit for Android

A minimal client for the [Grafana Faro](https://grafana.com/oss/faro/) receiver, for Kotlin/JVM and Android apps. It
posts events, logs, exceptions and measurements in the Faro web SDK's JSON format to a receiver such as Grafana
Alloy's `faro.receiver`. There is no OpenTelemetry, no tracing and no instrumentation: your code records what
happened, the library queues, scrubs, batches and sends it.

This is an independent project. It is not affiliated with, endorsed by or sponsored by Grafana Labs; "Grafana" and
"Faro" are used only to describe what the library talks to.

- The core (`telemetrykit`) is a plain Kotlin/JVM library: no Android dependencies, and it needs no Android SDK to use. The opt-in companion (`telemetrykit-androidx`, see below) is an Android library; building this repository needs an Android SDK for it.
- Dependency footprint of the core: kotlinx.coroutines, kotlinx.serialization and OkHttp (the transport only). The companion adds `androidx.lifecycle:lifecycle-process` and nothing else (see below).
- Every call on the `Telemetry` facade is a cheap enqueue that never throws.
- Nothing is sent without consent, a "build allows" gate and an enabled remote flag.

## Installation

From Maven Central:

<!-- x-release-please-start-version -->
```kotlin
// build.gradle.kts
dependencies {
    implementation("app.babyzzz.telemetrykit:telemetrykit:0.4.0")
}
```
<!-- x-release-please-end -->

Releases are signed and published to Maven Central, so `mavenCentral()` in your repositories is all you need. Requires JVM 17 bytecode (any Android app on AGP 8) and `java.time`: Android API 26+, or core library desugaring below that.

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

### User

```kotlin
Telemetry.setUser(subject)    // after a sign-in or a restored session: an opaque id
Telemetry.setUser(null)       // after a sign-out
```

Items carry the user (Faro `meta.user.id`, Loki `user_id`) from the moment they are recorded. A new id (none before,
or a different one) records one `user_identified` event in `domain`, carrying that user, so a session whose
`session_start` was recorded before the sign-in still names its user even when nothing else is recorded afterwards.
It is recorded like any other item: nothing without consent or with the remote flag off, held in memory while the flag
is unknown, and it counts as activity, so with no session running it starts one (`session_start`, then
`user_identified`, both with the user). The same id again, or `null`, records nothing.

## Lifecycle wiring

The core does not depend on AndroidX (the companion's `TelemetryLifecycle.install()` does this for you). Call `onStop()` and `onStart()` from the process lifecycle: on stop the queue
is written to the pending file and then sent (Android gives no grace period); on start the file is removed because the
queue is still in memory, and a session starts (`session_start`) when none is running, so sessions count people using
the app, not only those who hit an error.

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

## AndroidX companion (telemetrykit-androidx)

An opt-in artifact for Android apps that want the wiring done for them. The core stays SDK-free and does no automatic
instrumentation; the companion only does what you add it for.

<!-- x-release-please-start-version -->
```kotlin
// build.gradle.kts
dependencies {
    implementation("app.babyzzz.telemetrykit:telemetrykit-androidx:0.4.0")
}
```
<!-- x-release-please-end -->

**Dependency footprint.** `telemetrykit-androidx` depends on the core `telemetrykit` (api), `androidx.lifecycle:lifecycle-process`
(for `ProcessLifecycleOwner`) and the Kotlin stdlib, nothing else. In particular it does **not** depend on Compose:
Compose UI is `compileOnly`, and tap tracking switches itself on only when the app has Compose on its classpath (every
Compose-touching class is reached only after a one-time check, so an app without Compose never loads one). The core
`telemetrykit` depends on kotlinx.coroutines, kotlinx.serialization, OkHttp and the Kotlin stdlib, exactly as before. The
AAR ships consumer ProGuard rules that keep Activity class names readable (names only, for screen views).

### One call

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.start(...)                 // as in "Quick start"
        TelemetryAndroid.install(this)       // everything below, opt-out per feature
    }
}
```

`TelemetryAndroid.install(application, options)` is idempotent, never throws, and turns on:

| `AutoInstrumentation` | Default | What it does |
| --- | --- | --- |
| `lifecycle` | true | `TelemetryLifecycle.install()`: `Telemetry.onStart()` / `onStop()` from the process lifecycle |
| `exitReasons` | true | `ExitReasons.report(...)` once, on a background thread (API 30+) |
| `taps` | true | `click` events for taps on the Compose content of every Activity, **without** `Modifier.trackTaps` (needs Compose) |
| `activityViews` | true | screen views named after the resumed Activity's simple class name; coarse for a single-Activity app, use `TelemetryViews.changed` for finer names (once you call it, the automatic names stop) |
| `uncaughtExceptions` | true | `TelemetryCrashes.install()` |
| `tapIds` | `TapIds.Automatic` | what is sent as a tap's `element_id` (below) |
| `store` | SharedPreferences `telemetrykit` | the `TelemetryStore` for the exit-reason watermark (`SharedPreferencesTelemetryStore`) |

```kotlin
TelemetryAndroid.install(this, AutoInstrumentation(taps = false, tapIds = TapIds(setOf("save-button"), listOf("tile-*"))))
```

### The pieces

- `TelemetryLifecycle.install()`: registers a `ProcessLifecycleOwner` observer calling `Telemetry.onStart()` / `onStop()`.
  Idempotent; call it on the main thread (from another thread it is posted there).
- `TelemetryViews.changed(name: String?)`: `Telemetry.setView(name)` and, when the name differs from the last, one
  `view_changed` event (the view is in `meta.view.name`; no attributes). Names must be constants from your own
  enumeration, never data: they are not scrubbed.
- `ExitReasons.report(context, store, key = "telemetry_exit_watermark")`: API 30+ only (no-op below). Reads
  `ActivityManager.getHistoricalProcessExitReasons` (16 newest), and records one `app_exit` event for each newer than the
  stored timestamp, with `reason` (`crash`, `crash_native`, `anr`, `low_memory`, `excessive_resource_usage`,
  `user_requested`, `user_stopped`, `signaled`, `exit_self`, `initialization_failure`, `permission_change`,
  `dependency_died`, `other`, `freezer`, `package_state_change`, `package_updated`, `unknown`) and `importance`
  (`foreground`, `visible`, `service`, `background`, `cached`, `other`). The first run only stores the newest
  timestamp (no flood). It blocks on a system call: call it off the main thread. It never reads the trace stream
  (ANR thread dumps) or the description text.
- `TelemetryCrashes.install()`: see "Crashes" below.
- `Modifier.trackTaps(window, ids)`: tap tracking for one Compose root, for windows the automatic tracker cannot see
  (dialogs, bottom sheets, menus and popups are windows of their own: put it on their content root, with `window` =
  `"dialog"`, `"sheet"`, `"menu"`), or for apps that prefer explicit wiring. A tap seen both by the automatic tracker and by
  the modifier is recorded once. It requires Compose UI, which any caller has.

### Taps and privacy

A tap is recognised from pointer events (down and up within the touch slop and before the long-press timeout; nothing is
consumed, so the UI behaves as without it), then looked up in the unmerged semantics tree: the deepest enabled node with a
click action, and on it or its ancestors the nearest test tag. One `click` event is recorded with `element_id`,
`element_tag` (from the role: `button`, `switch`, `checkbox`, `radiobutton`, `tab`, `dropdownlist`, `image`, else
`clickable`) and `element_class` (the window, a constant). **Label text is never read or sent.**

`element_id` comes from the test tag through `TapIds`:

- `TapIds.Automatic` (default, nothing to maintain): a tag is sent when it looks like a constant id (starts with a letter,
  then letters, digits and `_ . : -`, at most 64 characters, no run of four or more digits, no `@`, and unchanged by the core's
  `TelemetryScrub`); anything else is sent as `[masked]`.
- Strict, `TapIds(literals = setOf("save-button"), templates = listOf("monitor-tile-*"))`: a tag in `literals` is sent as is, a
  tag matching a template (`*` matches any text) is sent as the template, every other tag is `[masked]`. **Recommended when
  your tags can embed user data** (a camera name, an e-mail, an id in an interpolated tag): the automatic rule cannot know
  that `camera-nursery` is a name.
- A tappable without a tag is sent with an empty `element_id`, so gaps show in the data.

Other privacy rules: no text, no coordinates, no screen contents; the exit-reason `description` and traces are never read;
crash values are scrubbed; every call is safe and never throws into the app.

### Crashes

`TelemetryCrashes.install()` (on by default in `TelemetryAndroid.install`) chains a default `UncaughtExceptionHandler`. It
remembers the handler that was installed before it and **always** calls it afterwards, in a `finally`, so Crashlytics (or the
platform) still receives the crash and the process dies the normal way. Installation order relative to Crashlytics does not
matter for delivery: whichever installs later calls the earlier one. Install it once (it is idempotent; it never wraps
itself twice).

It records a Faro exception through the core like `Telemetry.error`: `type` is the class simple name, `value` the core's
`errorKind` plus the scrubbed message, **no stack frames**; context `where` = `uncaught`, `thread` = `main` or `background`
(never the thread's name) and `fatal` = `true`. Then the queue is written to the pending file synchronously
(`TelemetryClient.persist()`): nothing is sent from the dying process, the next start restores the file and sends it. The
whole handler is bounded to one second, swallows every Throwable of its own, and a crash while recording is not recorded
again. The core's gates apply (consent, remote flag, a started client). The next start's `app_exit` with reason `crash`
is an independent second signal of the same crash.

### Unreleased builds

```kotlin
substitute(module("app.babyzzz.telemetrykit:telemetrykit-androidx")).using(project(":androidx"))
```

next to the core's `substitute(...)` in a composite build; `./gradlew publishToMavenLocal -Pversion=0.2.0-local` publishes both
artifacts.

## Errors and R8 / ProGuard

`Telemetry.error` sends the exception's class simple name as `type` and, as `value`, `errorKind(error)` followed by the
message, scrubbed and cut at `maxErrorValueLength`. No stack frames are sent. Pass an `errorKind` hook in
`Configuration` to group by something else (an HTTP status, a domain code). Because the default is the class name, keep
exception names readable in minified builds:

```
-keepnames class com.example.** extends java.lang.Throwable
-keepnames class okhttp3.** extends java.lang.Throwable
```

Name your own packages and the libraries whose errors you report. Platform types (`java.*`, `javax.*`, `android.*`) are
never renamed. Don't use `class * extends java.lang.Throwable`: if your app bundles a library that re-implements a
platform package, such as `org.json` (socket.io-client brings it), its exception keeps the platform's name, the
platform's class (with a different superclass) wins at run time, and the minified app fails verification at start-up.

The core is a plain JVM jar, so it ships no consumer rules (the AndroidX companion ships its own); add the lines to your app's `proguard-rules.pro`. The library's
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
./gradlew build                  # core and companion; needs an Android SDK (ANDROID_HOME)
./gradlew publishToMavenLocal     # both artifacts, unsigned
```

## License

ISC License, copyright 2026 Baby Zzz's LLC. See [LICENSE](LICENSE).
