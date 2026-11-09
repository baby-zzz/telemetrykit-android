package app.babyzzz.telemetrykit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The collector's answer: the HTTP status and `Retry-After` in seconds (a 429 or 503) */
public data class TelemetryResponse(val status: Int, val retryAfterSeconds: Long? = null)

/** Sends one batch to the Faro receiver; throws when there was no answer (tests replace it). */
public fun interface TelemetryTransport {
    public suspend fun post(body: ByteArray): TelemetryResponse
}

/**
 * The collector's own client, built from scratch and never taken from the host: no certificate pinner, no interceptors,
 * no cookies (OkHttp's default is none), no cache, no redirects. A pin failure must never be reported through
 * telemetry, and the collector must not ride on the host's pinned or intercepted client. Created on the first post, so
 * a host that never sends costs nothing.
 */
public class OkHttpTelemetryTransport(private val endpoint: String) : TelemetryTransport {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun post(body: ByteArray): TelemetryResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            TelemetryResponse(response.code, response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
