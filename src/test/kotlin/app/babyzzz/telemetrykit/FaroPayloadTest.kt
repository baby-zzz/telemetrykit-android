package app.babyzzz.telemetrykit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Faro wire format  */
class FaroPayloadTest {
    private fun meta(user: String? = null) = FaroMeta(
        app = TelemetryFixtures.app,
        sdk = FaroMeta.Sdk("telemetrykit-android", "0.1.0"),
        session = FaroMeta.Session("s", mapOf("platform" to "android")),
        user = user?.let { FaroMeta.User(it) },
        view = null,
        browser = TelemetryFixtures.browser,
    )

    private fun encode(payload: FaroPayload): JsonObject =
        Json.parseToJsonElement(FaroPayload.json.encodeToString(FaroPayload.serializer(), payload)).jsonObject

    @Test
    fun `carries the platform fields the dashboards filter on`() {
        val payload = FaroPayload.of(meta("sub-1"), listOf(TelemetryItem.Event(FaroEvent("session_start", "android", "t", null))))
        val json = encode(payload)
        val meta = json.getValue("meta").jsonObject
        val app = meta.getValue("app").jsonObject
        assertEquals("my-app", app.getValue("name").jsonPrimitive.content)
        assertEquals("android", app.getValue("namespace").jsonPrimitive.content)
        assertEquals("com.example.app", app.getValue("bundleId").jsonPrimitive.content)
        assertEquals("development", app.getValue("environment").jsonPrimitive.content)
        assertEquals("android", meta.getValue("session").jsonObject.getValue("attributes").jsonObject.getValue("platform").jsonPrimitive.content)
        assertEquals("sub-1", meta.getValue("user").jsonObject.getValue("id").jsonPrimitive.content)
        val browser = meta.getValue("browser").jsonObject
        assertEquals("MyApp-Android", browser.getValue("name").jsonPrimitive.content)
        assertTrue(browser.getValue("mobile").jsonPrimitive.boolean)
        assertEquals("telemetrykit-android", meta.getValue("sdk").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(1, json.getValue("events").jsonArray.size)
        assertEquals("android", json.getValue("events").jsonArray[0].jsonObject.getValue("domain").jsonPrimitive.content)
        // Absent kinds and fields are left out, not empty or null
        assertTrue("logs" !in json && "exceptions" !in json && "measurements" !in json)
        assertTrue("view" !in meta)
        assertTrue("attributes" !in json.getValue("events").jsonArray[0].jsonObject)
    }

    @Test
    fun `every signal kind goes in its own array with Faro's field names`() {
        val items = listOf(
            TelemetryItem.Log(FaroLog("m", FaroLog.Level.ERROR, "t", mapOf("category" to "auth"))),
            TelemetryItem.Exception(FaroException("ApiError", "http_503", "t", mapOf("where" to "alert"))),
            TelemetryItem.Measurement(FaroMeasurement("app_launch", mapOf("cold_ms" to 812.0), "t")),
        )
        val json = encode(FaroPayload.of(meta(), items))
        assertEquals("error", json.getValue("logs").jsonArray[0].jsonObject.getValue("level").jsonPrimitive.content)
        assertEquals("ApiError", json.getValue("exceptions").jsonArray[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("http_503", json.getValue("exceptions").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(812.0, json.getValue("measurements").jsonArray[0].jsonObject.getValue("values").jsonObject.getValue("cold_ms").jsonPrimitive.content.toDouble(), 0.0)
        assertNull(json["events"])
    }

    @Test
    fun `timestamps are RFC 3339 with milliseconds`() {
        assertEquals("2026-11-09T04:53:20.250Z", FaroTimestamp.string(1_794_200_000_250))
    }

    @Test
    fun `items survive the pending file round trip`() {
        val pending = TelemetryClient.Pending(meta("u"), TelemetryItem.Log(FaroLog("m", FaroLog.Level.WARN, "t", null)), 5)
        val text = FaroPayload.json.encodeToString(TelemetryClient.Pending.serializer(), pending)
        assertTrue(text.contains("\"log\""))
        assertEquals(pending, FaroPayload.json.decodeFromString(TelemetryClient.Pending.serializer(), text))
    }
}
