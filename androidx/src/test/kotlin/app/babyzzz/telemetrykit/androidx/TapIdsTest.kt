package app.babyzzz.telemetrykit.androidx

import org.junit.Assert.assertEquals
import org.junit.Test

class TapIdsTest {
    private val strict = TapIds(setOf("save-button", "tab-monitor"), listOf("monitor-tile-*", "alert-*-snooze"))

    @Test
    fun `strict sends a listed literal as it is`() = assertEquals("save-button", strict.resolve("save-button"))

    @Test
    fun `strict sends a template match as the template`() {
        assertEquals("monitor-tile-*", strict.resolve("monitor-tile-Nursery"))
        assertEquals("alert-*-snooze", strict.resolve("alert-123-snooze"))
    }

    @Test
    fun `strict masks everything else and sends empty for no tag`() {
        assertEquals("[masked]", strict.resolve("member-a@example.com"))
        assertEquals("[masked]", strict.resolve("other"))
        assertEquals("", strict.resolve(null))
        assertEquals("", strict.resolve(""))
    }

    @Test
    fun `a template is not a regex`() = assertEquals("[masked]", TapIds(emptySet(), listOf("a.b*")).resolve("axb1"))

    @Test
    fun `automatic sends constant looking ids`() {
        val ids = TapIds.Automatic
        for (tag in listOf("save-button", "tab_monitor", "page.title", "a:b", "x1", "profile-button")) assertEquals(tag, ids.resolve(tag))
    }

    @Test
    fun `automatic masks data like tags`() {
        val ids = TapIds.Automatic
        for (
            tag in listOf(
                "member-a@example.com", "camera-12345", "1abc", "has space", "-lead", "x".repeat(65), "ünï",
                "go?token=abc", "Bearer abcdef",
            )
        ) assertEquals(tag, "[masked]", ids.resolve(tag))
        assertEquals("", ids.resolve(null))
    }

    @Test
    fun `automatic allows up to three digits in a row`() {
        assertEquals("row-123", TapIds.Automatic.resolve("row-123"))
        assertEquals("[masked]", TapIds.Automatic.resolve("row-1234"))
        assertEquals("x".repeat(64), TapIds.Automatic.resolve("x".repeat(64)))
    }
}
