package app.babyzzz.telemetrykit.androidx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@Composable
private fun Tappables() {
    Column {
        Box(Modifier.size(50.dp).testTag("save-button").clickable(role = Role.Button) {})
        Box(Modifier.size(50.dp).clickable(role = Role.Button) {})
        Box(Modifier.size(50.dp).testTag("off-button").clickable(enabled = false, role = Role.Button) {})
        Box(Modifier.size(50.dp).testTag("monitor-tile-Nursery").clickable {})
        Box(Modifier.size(50.dp).testTag("member-a@example.com").clickable(role = Role.Checkbox) {})
        Box(Modifier.size(50.dp).testTag("sound-toggle").toggleable(value = false, role = Role.Switch, onValueChange = {}))
    }
}

@RunWith(RobolectricTestRunner::class)
class TrackTapsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var telemetry: TestTelemetry

    @Before
    fun setUp() {
        TapReporter.resetForTest()
        telemetry = TestTelemetry()
    }

    @After
    fun tearDown() = telemetry.close()

    private fun clicks(setup: () -> Unit = {}) = telemetry.events().filter { it.name == "click" }.map { it.attributes }.also { setup() }

    private val strict = TapIds(setOf("save-button", "sound-toggle"), listOf("monitor-tile-*"))

    private fun tap(tag: String, ids: TapIds = strict, window: String = "main") {
        rule.setContent { Box(Modifier.trackTaps(window, ids)) { Tappables() } }
        rule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun `a tagged button records one click with id, role and window`() {
        tap("save-button")
        assertEquals(listOf(mapOf("element_id" to "save-button", "element_tag" to "button", "element_class" to "main")), clicks())
    }

    @Test
    fun `a node without a tag sends an empty id`() {
        rule.setContent { Box(Modifier.trackTaps("dialog", strict)) { Tappables() } }
        rule.onAllNodes(hasClickAction())[1].performClick()
        assertEquals(listOf(mapOf("element_id" to "", "element_tag" to "button", "element_class" to "dialog")), clicks())
    }

    @Test
    fun `a disabled node records nothing`() {
        tap("off-button")
        assertEquals(emptyList<Map<String, String>?>(), clicks())
    }

    @Test
    fun `a template match sends the template, a clickable without a role is clickable`() {
        tap("monitor-tile-Nursery")
        assertEquals(listOf(mapOf("element_id" to "monitor-tile-*", "element_tag" to "clickable", "element_class" to "main")), clicks())
    }

    @Test
    fun `an id that is not allowed is masked in strict mode and in automatic mode`() {
        tap("member-a@example.com")
        assertEquals(listOf(mapOf("element_id" to "[masked]", "element_tag" to "checkbox", "element_class" to "main")), clicks())
    }

    @Test
    fun `a switch is a switch and automatic mode sends a constant looking tag`() {
        tap("sound-toggle", TapIds.Automatic, "sheet")
        assertEquals(listOf(mapOf("element_id" to "sound-toggle", "element_tag" to "switch", "element_class" to "sheet")), clicks())
    }

    @Test
    fun `the modifier does not consume the tap`() {
        var clicked = 0
        rule.setContent { Box(Modifier.trackTaps("main", strict)) { Box(Modifier.size(50.dp).testTag("save-button").clickable { clicked++ }) } }
        rule.onNodeWithTag("save-button").performClick()
        assertEquals(1, clicked)
        assertEquals(1, clicks().size)
    }
}
