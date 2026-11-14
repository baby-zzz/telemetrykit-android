package app.babyzzz.telemetrykit.androidx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapRecognizerTest {
    private fun recognizer() = TapRecognizer(slop = 10f, longPressMillis = 400)

    @Test
    fun `a quick press and release is a tap`() {
        val r = recognizer()
        r.down(5f, 5f, 0, 1)
        r.move(8f, 8f, 1)
        assertTrue(r.up(8f, 8f, 100))
    }

    @Test
    fun `a drag beyond the slop is not a tap`() {
        val r = recognizer()
        r.down(0f, 0f, 0, 1)
        r.move(50f, 0f, 1)
        assertFalse(r.up(0f, 0f, 100))
    }

    @Test
    fun `a long press is not a tap`() {
        val r = recognizer()
        r.down(0f, 0f, 0, 1)
        assertFalse(r.up(0f, 0f, 400))
    }

    @Test
    fun `a second pointer or a cancel ruins it`() {
        val r = recognizer()
        r.down(0f, 0f, 0, 1)
        r.move(0f, 0f, 2)
        assertFalse(r.up(0f, 0f, 10))
        r.down(0f, 0f, 0, 1)
        r.cancel()
        assertFalse(r.up(0f, 0f, 10))
        r.down(0f, 0f, 0, 2)
        assertFalse(r.up(0f, 0f, 10))
    }

    @Test
    fun `an up without a down is not a tap`() = assertFalse(recognizer().up(0f, 0f, 1))
}
