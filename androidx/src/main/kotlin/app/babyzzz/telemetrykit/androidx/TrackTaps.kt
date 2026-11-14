package app.babyzzz.telemetrykit.androidx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * Tap tracking for one Compose root. Put it on the root of a window the automatic tracker cannot see: a dialog, a
 * bottom sheet, a menu or any popup (each is a window of its own), or on the main content when you prefer explicit
 * wiring ([TelemetryAndroid] does the main window by itself, and a tap both see is recorded once).
 *
 * It observes pointer events in the Initial pass and never consumes them, so the UI behaves exactly as without it. A
 * tap (down and up within the touch slop and before the long-press timeout) is looked up in the unmerged semantics
 * tree: the deepest enabled node with a click action, and on it or its ancestors the nearest test tag. It records one
 * `click` event with `element_id` ([ids] decides what is sent for the tag; "" without a tag), `element_tag` (from the
 * role: button, switch, checkbox, radiobutton, tab, dropdownlist, image, else clickable) and `element_class` = [window]
 * (a constant: "main", "dialog", "sheet", "menu"). Never any text. Taps on nothing clickable record nothing.
 *
 * Requires Compose UI, which any caller of this function has: the companion does not bring Compose in itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.trackTaps(window: String, ids: TapIds = TapIds.Automatic): Modifier = composed {
    val view = LocalView.current
    val configuration = LocalViewConfiguration.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coordinates = it }.pointerInput(window, ids) {
        val recognizer = TapRecognizer(configuration.touchSlop, configuration.longPressTimeoutMillis)
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                try {
                    val change = event.changes.firstOrNull() ?: continue
                    val pressed = event.changes.count { it.pressed }
                    when (event.type) {
                        PointerEventType.Press -> recognizer.down(change.position.x, change.position.y, change.uptimeMillis, pressed)
                        PointerEventType.Move -> recognizer.move(change.position.x, change.position.y, pressed)
                        PointerEventType.Release -> {
                            if (pressed == 0 && recognizer.up(change.position.x, change.position.y, change.uptimeMillis)) {
                                val owner = TapSemantics.rootOf(view)?.semanticsOwner
                                val origin = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                                val target = owner?.let { TapSemantics.find(it, origin.x + change.position.x, origin.y + change.position.y) }
                                if (target != null) TapReporter.report(change.uptimeMillis, target, window, ids)
                            }
                        }
                        else -> Unit
                    }
                } catch (_: Exception) {
                    recognizer.cancel()
                }
            }
        }
    }
}
