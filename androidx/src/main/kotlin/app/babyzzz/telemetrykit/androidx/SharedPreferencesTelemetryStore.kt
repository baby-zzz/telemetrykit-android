package app.babyzzz.telemetrykit.androidx

import android.content.Context
import android.content.SharedPreferences
import app.babyzzz.telemetrykit.TelemetryStore

/** A [TelemetryStore] over SharedPreferences (file `telemetrykit` by default); non-secret values only */
public class SharedPreferencesTelemetryStore(private val prefs: SharedPreferences) : TelemetryStore {
    public constructor(context: Context, name: String = DEFAULT_NAME) : this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?): Unit = prefs.edit().also { if (value == null) it.remove(key) else it.putString(key, value) }.apply()

    public companion object {
        public const val DEFAULT_NAME: String = "telemetrykit"
    }
}
