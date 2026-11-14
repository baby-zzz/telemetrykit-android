# AGENTS.md

Instructions for coding agents (and humans) working on TelemetryKit for Android.

## Commits
- Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `ci:`, `build:`, `chore:`; `!` for breaking).
- **No `Co-Authored-By` or other trailers** in commit messages, whatever a tool's default is.
- Keep commits focused; update `CHANGELOG.md` for user-visible changes.

## Code
- Two modules, explicit API mode, JVM target 17, no new warnings: the root project is the plain Kotlin/JVM core (`telemetrykit`, no Android SDK, must stay the root project: apps substitute it with `project(":")`), and `androidx/` is the opt-in Android companion (`telemetrykit-androidx`). The companion's POM may list only the core, `lifecycle-process` and the Kotlin stdlib (Compose is `compileOnly`, guarded by `ComposeSupport`; `./gradlew check` runs `verifyPom`). Never add an Android dependency to the core.
- Dependencies are pinned exactly in `gradle/libs.versions.toml`; add new ones only with a good reason.
- Nothing app-specific in the library: endpoints, names, keys and limits are `Configuration`.
- The OkHttp transport builds its own client; never accept or share the host app's client.
- Privacy first: every free string is scrubbed (`TelemetryScrub`) before it is queued; keep the scrub failing closed.
- The wire format is the Grafana Faro receiver's JSON (`FaroPayload`); keep it compatible with Alloy's `faro.receiver`.

## Checks
- `./gradlew build` must pass (compile, JUnit and Robolectric tests, lint); it needs an Android SDK (`ANDROID_HOME`, platform 36, build-tools 36). Don't commit `local.properties`.
- CI: GitHub Actions (`.github/workflows/ci.yml`).

## Releases
- Development happens on the maintainers' own Git server; GitHub is the public mirror. Releases are cut there by a
  release job, never on GitHub: it derives the next version from the Conventional Commits since the last `vX.Y.Z` tag
  (`feat` bumps the minor version, `fix`/`perf` the patch; before 1.0 a breaking `!` bumps the minor), writes the
  `CHANGELOG.md` section and the version strings marked `x-release-please-version`, commits `chore(release): vX.Y.Z`,
  tags, runs the tests and, after an approval, pushes to GitHub and creates the GitHub Release with the notes.
- On Android, the release job then signs and publishes the tag to Maven Central (`app.babyzzz.telemetrykit:telemetrykit` and `telemetrykit-androidx`).
- Don't edit versions or the changelog by hand, and keep the version markers.
- Contributions on GitHub are welcome as pull requests; maintainers bring merged changes into the main repository.
