package app.babyzzz.telemetrykit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * A telemetry session: a random id that lasts until [Configuration.sessionInactivityMillis] without activity, persisted
 * across launches in the [TelemetryStore]. Unlike the Faro web SDK there is no maximum length: a phone streaming all
 * night is one session. A new session sends `session_start`. Times are epoch milliseconds.
 */
@Serializable
public data class TelemetrySession(
    @SerialName("id") val id: String,
    @SerialName("startedAt") val startedAt: Long,
    @SerialName("lastActivity") val lastActivity: Long,
) {
    /** [session] is the one an item recorded at `now` belongs to, [isNew] whether it started just now */
    public data class Step(val session: TelemetrySession, val isNew: Boolean)

    public fun save(store: TelemetryStore, key: String) {
        runCatching { store.putString(key, Json.encodeToString(serializer(), this)) }
    }

    public companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val random = SecureRandom()

        public fun next(
            current: TelemetrySession?,
            now: Long,
            inactivityMillis: Long,
            newId: () -> String = ::randomId,
        ): Step {
            if (current != null && now - current.lastActivity < inactivityMillis && now >= current.startedAt) {
                return Step(current.copy(lastActivity = maxOf(current.lastActivity, now)), false)
            }
            return Step(TelemetrySession(newId(), now, now), true)
        }

        /** 10 alphanumerics, the Faro web SDK's session id format */
        public fun randomId(): String = String(CharArray(10) { ALPHABET[random.nextInt(ALPHABET.length)] })

        public fun load(store: TelemetryStore, key: String): TelemetrySession? = runCatching {
            store.getString(key)?.let { Json.decodeFromString(serializer(), it) }
        }.getOrNull()
    }
}
