package app.babyzzz.telemetrykit

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The collector's own client against a local MockWebServer (never the real collector) */
class OkHttpTelemetryTransportTest {
    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    private fun transport() = OkHttpTelemetryTransport(server.url("/collect").toString())

    @Test
    fun `posts the body as JSON without cookies or other headers of ours`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val response = transport().post("""{"meta":{}}""".toByteArray())
        assertEquals(TelemetryResponse(204, null), response)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/collect", request.path)
        assertEquals("""{"meta":{}}""", request.body.readUtf8())
        assertEquals("application/json; charset=utf-8".substringBefore(";"), request.getHeader("Content-Type")?.substringBefore(";"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-App-Version"))
    }

    @Test
    fun `reads Retry-After in seconds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(TelemetryResponse(429, 120), transport().post(ByteArray(0)))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(TelemetryResponse(503, null), transport().post(ByteArray(0)))
    }

    @Test
    fun `does not follow redirects`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        assertEquals(302, transport().post(ByteArray(0)).status)
        assertEquals(1, server.requestCount)
    }
}
