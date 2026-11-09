package app.babyzzz.telemetrykit

import org.junit.Assert.assertEquals
import org.junit.Test

/** The default scrub patterns */
class TelemetryScrubTest {
    private val scrub = TelemetryScrub.default
    private val redacted = TelemetryScrub.REDACTED
    private val email = TelemetryScrub.EMAIL_REDACTED

    @Test
    fun `scrubs credential query values`() {
        assertEquals("https://api.example.com/invite/1?signed=$redacted", scrub.text("https://api.example.com/invite/1?signed=r_1-e_2-abc"))
        assertEquals(
            "https://example.com/auth/callback?state=$redacted&code=$redacted&lang=es",
            scrub.text("https://example.com/auth/callback?state=s1&code=c1&lang=es"),
        )
        assertEquals("GET /reset/u?ts=$redacted&Signed=$redacted#top", scrub.text("GET /reset/u?ts=17&Signed=x#top"))
        assertEquals("no query here", scrub.text("no query here"))
    }

    @Test
    fun `a secret value stops at a quote, which a looser pattern would not`() {
        assertEquals("""{"u":"/x?token=$redacted"}""", scrub.text("""{"u":"/x?token=abc"}"""))
        assertEquals("""'/x?code=$redacted'""", scrub.text("'/x?code=abc'"))
    }

    @Test
    fun `scrubs e-mails but not package versions`() {
        assertEquals("Invite failed for $email: 409", scrub.text("Invite failed for jane.doe+kid@example.co.uk: 409"))
        assertEquals(
            "https://example.com/register?email=$email&lang=es",
            scrub.text("https://example.com/register?email=jane@example.com&lang=es"),
        )
        val stack = "at render@https://example.com/assets/index.js:1:2 (faro-web-sdk@2.12.1)"
        assertEquals(stack, scrub.text(stack))
    }

    @Test
    fun `scrubs bearer tokens and URL credentials`() {
        assertEquals("Authorization: Bearer [REDACTED]", scrub.text("Authorization: Bearer eyJ.abc-def"))
        assertEquals("rtsp://[REDACTED]@192.168.1.20:554/stream1", scrub.text("rtsp://admin:hunter2@192.168.1.20:554/stream1"))
    }

    @Test
    fun `fails closed when the patterns are unavailable`() {
        assertEquals(redacted, scrub.text("anything at all", null))
    }

    @Test
    fun `items are scrubbed but names kept`() {
        val item = TelemetryItem.Log(FaroLog("Secure write failed", FaroLog.Level.WARN, "t", mapOf("url" to "/x?token=1")))
        val log = (item.scrubbed(scrub) as TelemetryItem.Log).log
        assertEquals("Secure write failed", log.message)
        assertEquals(mapOf("url" to "/x?token=[REDACTED]"), log.context)
        assertEquals(FaroLog.Level.WARN, log.level)
        val exception = TelemetryItem.Exception(FaroException("ApiError", "http_503 a@b.com", "t", mapOf("where" to "alert")))
        assertEquals("http_503 [email]", (exception.scrubbed(scrub) as TelemetryItem.Exception).exception.value)
    }
}
