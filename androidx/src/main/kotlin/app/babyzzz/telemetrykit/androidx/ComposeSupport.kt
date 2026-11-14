package app.babyzzz.telemetrykit.androidx

/**
 * Whether the app has Compose UI. The companion does not depend on Compose (it is `compileOnly`): everything that
 * touches Compose classes is only reached after this says yes, so an app without Compose never loads them.
 */
internal object ComposeSupport {
    val present: Boolean by lazy {
        try {
            Class.forName("androidx.compose.ui.platform.AndroidComposeView", false, ComposeSupport::class.java.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
