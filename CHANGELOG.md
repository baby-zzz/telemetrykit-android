# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.2.0](https://github.com/baby-zzz/telemetrykit-android/compare/telemetrykit-android-v0.1.0...telemetrykit-android-v0.2.0) (2026-11-09)


### Features

* TelemetryKit 0.1.0, a minimal Grafana Faro receiver client ([42e5fe9](https://github.com/baby-zzz/telemetrykit-android/commit/42e5fe96d0cc6f69335e25d597e66d2a9d58171e))

## [0.1.0] - 2026-11-09

### Added
- First release: a minimal Faro receiver client for Kotlin/JVM and Android with no OpenTelemetry dependency.
- `Telemetry` facade: events, throttled events, measurements, handled errors, warning/error logs, user and view,
  consent, a tri-state remote gate and `onStart()`/`onStop()` lifecycle hooks.
- `TelemetryClient` queue: batching, one drain at a time, grouping by meta, backoff with `Retry-After`, a pending file
  that survives restarts, and sessions with an inactivity timeout and no maximum length.
- `TelemetryTransport` interface and an OkHttp implementation with its own client (no pinning, interceptors, cookies,
  cache or redirects).
- `TelemetryScrub`: secret query parameters, e-mail addresses, bearer tokens and `scheme://user:pass@`, fail closed,
  with extra parameter names and regexes through `Configuration`.
- `TelemetryStore` interface and `InMemoryTelemetryStore`.
