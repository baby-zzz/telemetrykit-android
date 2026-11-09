# AGENTS.md

Instructions for coding agents (and humans) working on TelemetryKit for Android.

## Commits
- Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `ci:`, `build:`, `chore:`; `!` for breaking).
- **No `Co-Authored-By` or other trailers** in commit messages, whatever a tool's default is.
- Keep commits focused; update `CHANGELOG.md` for user-visible changes.

## Code
- Plain Kotlin/JVM library (no Android SDK), explicit API mode, JVM target 17; no new warnings.
- Dependencies are pinned exactly in `gradle/libs.versions.toml`; add new ones only with a good reason.
- Nothing app-specific in the library: endpoints, names, keys and limits are `Configuration`.
- The OkHttp transport builds its own client; never accept or share the host app's client.
- Privacy first: every free string is scrubbed (`TelemetryScrub`) before it is queued; keep the scrub failing closed.
- The wire format is the Grafana Faro receiver's JSON (`FaroPayload`); keep it compatible with Alloy's `faro.receiver`.

## Checks
- `./gradlew build` must pass (compile and JUnit tests).
- CI: GitHub Actions (`.github/workflows/ci.yml`).
