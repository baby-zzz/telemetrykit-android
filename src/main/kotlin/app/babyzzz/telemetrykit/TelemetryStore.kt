package app.babyzzz.telemetrykit

/**
 * The small key-value store the client needs: the session (so it survives a restart) and the user's consent. Back it
 * with SharedPreferences, DataStore or anything else; non-secret values only. Calls may come from any thread.
 */
public interface TelemetryStore {
    public fun getString(key: String): String?

    /** A null [value] removes the key */
    public fun putString(key: String, value: String?)

    public fun getBoolean(key: String): Boolean? = getString(key)?.toBooleanStrictOrNull()

    public fun putBoolean(key: String, value: Boolean?): Unit = putString(key, value?.toString())

    public fun remove(key: String): Unit = putString(key, null)
}

/** A store in memory, for tests and for hosts that do not want anything kept between launches */
public class InMemoryTelemetryStore(initial: Map<String, String> = emptyMap()) : TelemetryStore {
    private val map = java.util.concurrent.ConcurrentHashMap(initial)

    public val values: Map<String, String> get() = map.toMap()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
