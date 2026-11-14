package app.babyzzz.telemetrykit.androidx

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Hit-testing of the unmerged semantics tree. Reads tags and roles only, never text. Everything that touches Compose
 * classes is in this file (and `TrackTaps.kt`), reached only after [ComposeSupport.present].
 */
internal object TapSemantics {
    /** A tap at ([x], [y]) in [decor]'s window: found in the topmost Compose root that has a clickable there, and reported */
    fun tapAt(decor: View, x: Float, y: Float, upTime: Long, window: String, ids: TapIds): Boolean {
        for (root in roots(decor)) {
            val location = IntArray(2)
            (root as View).getLocationInWindow(location)
            val target = find(root.semanticsOwner, x - location[0], y - location[1]) ?: continue
            TapReporter.report(upTime, target, window, ids)
            return true
        }
        return false
    }

    /** The compose roots under [view], topmost (last drawn) first */
    fun roots(view: View): List<RootForTest> {
        if (view.visibility != View.VISIBLE) return emptyList()
        if (view is RootForTest) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (view.childCount - 1 downTo 0).flatMap { roots(view.getChildAt(it)) }
    }

    fun rootOf(view: View): RootForTest? = view as? RootForTest ?: roots(view).firstOrNull()

    /** The deepest enabled clickable node containing the point ([x], [y] in the compose root's pixels), topmost first */
    fun find(owner: SemanticsOwner, x: Float, y: Float): TapTarget? {
        val node = hit(owner.unmergedRootSemanticsNode, x, y) ?: return null
        var tag: String? = null
        var ancestor: SemanticsNode? = node
        while (ancestor != null && tag == null) {
            tag = ancestor.config.getOrNull(SemanticsProperties.TestTag)
            ancestor = ancestor.parent
        }
        return TapTarget(tag, elementTag(node.config.getOrNull(SemanticsProperties.Role)))
    }

    private fun hit(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        if (!node.layoutInfo.isPlaced) return null
        val bounds = node.boundsInRoot
        if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) return null
        // Later siblings are drawn on top
        for (child in node.children.asReversed()) {
            hit(child, x, y)?.let { return it }
        }
        val config = node.config
        return if (SemanticsActions.OnClick in config && SemanticsProperties.Disabled !in config) node else null
    }

    internal fun elementTag(role: Role?): String = when (role) {
        Role.Button -> "button"
        Role.Switch -> "switch"
        Role.Checkbox -> "checkbox"
        Role.RadioButton -> "radiobutton"
        Role.Tab -> "tab"
        Role.DropdownList -> "dropdownlist"
        Role.Image -> "image"
        else -> "clickable"
    }
}
