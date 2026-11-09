package app.babyzzz.telemetrykit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The telemetry queue: records items with the meta of the moment, batches them (every [Configuration.flushDelayMillis], at [Configuration.maxBatch] items, or when
 * the app leaves), retries failures with backoff and keeps what could not be sent in a small file until the next launch.
 * Nothing is sent unless both the user's setting ([setConsent], on by default) and the host's remote flag ([setRemote]) allow
 * it; while the flag is not known yet items wait in memory, and a "no" from either drops everything queued. All state is
 * behind one lock with no I/O inside, so it can be called from any thread. Only one drain runs at a time.
 */
public class TelemetryClient(
    public val configuration: Configuration,
    private val transport: TelemetryTransport,
    private val store: TelemetryStore,
    private val storeFile: File?,
    /** Whether the user's consent is on at start (see [Telemetry.consent]) */
    consent: Boolean,
    private val scope: CoroutineScope,
    /** Epoch milliseconds; injected so backoff and sessions never depend on the wall clock in tests */
    private val now: () -> Long = System::currentTimeMillis,
) {
    public enum class RemoteGate {
        /** No answer from the host yet: record, but only in memory */
        UNKNOWN,
        ENABLED,
        DISABLED,
    }

    @Serializable
    public data class Pending(val meta: FaroMeta, val item: TelemetryItem, val recordedAt: Long)

    private class Throttle(val sentAt: Long, var suppressed: Int)

    private val lock = Any()

    // The state below is only touched inside synchronized(lock)
    private var pending = ArrayList<Pending>()
    private var session: TelemetrySession? = TelemetrySession.load(store, configuration.sessionKey)
    private var userId: String? = null
    private var view: String? = null
    private var consent = consent
    private var remote = RemoteGate.UNKNOWN
    private var flushScheduled = false
    private var sending = false

    /** The running drain; a [flush] meanwhile joins it, then drains what it missed */
    private var drain: Deferred<Boolean>? = null
    private var notBefore: Long? = null
    private var failures = 0
    private val throttled = HashMap<String, Throttle>()
    private var sessionSavedAt = 0L

    // region Gates and identity

    /** The injected clock, for the timestamps of items the facade builds */
    public val nowMillis: Long get() = now()

    public val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** Both gates are open: items are being sent */
    public val isOpen: Boolean get() = synchronized(lock) { consent && remote == RemoteGate.ENABLED }
    public val currentSession: TelemetrySession? get() = synchronized(lock) { session }

    /** The user's setting, also written to the store under [Configuration.consentKey]: off drops everything queued or stored, at once */
    public fun setConsent(consent: Boolean) {
        runCatching { store.putBoolean(configuration.consentKey, consent) }
        val open = synchronized(lock) {
            this.consent = consent
            if (!consent) pending.clear()
            consent && remote == RemoteGate.ENABLED
        }
        if (consent) {
            if (open) scheduleFlush(0)
        } else {
            deleteStore()
        }
    }

    /** The host's remote flag (kill switch, rollout) */
    public fun setRemote(gate: RemoteGate) {
        val open = synchronized(lock) {
            remote = gate
            if (gate == RemoteGate.DISABLED) pending.clear()
            consent && gate == RemoteGate.ENABLED
        }
        if (gate == RemoteGate.DISABLED) deleteStore()
        if (open) scheduleFlush(0)
    }

    /** An opaque user id after a sign-in, null after a sign-out; items recorded from now on carry it */
    public fun setUser(id: String?) {
        synchronized(lock) { userId = id?.takeIf { it.isNotEmpty() } }
    }

    public fun setView(name: String?) {
        synchronized(lock) { view = name }
    }

    // endregion

    // region Recording

    public fun record(item: TelemetryItem) {
        val date = now()
        val clean = item.scrubbed(configuration.scrub)
        var flushIn: Long? = null
        var toSave: TelemetrySession? = null
        synchronized(lock) {
            if (!consent || remote == RemoteGate.DISABLED) return
            val step = TelemetrySession.next(session, date, configuration.sessionInactivityMillis)
            session = step.session
            val meta = meta(step.session)
            if (step.isNew) {
                val start = FaroEvent("session_start", configuration.domain, FaroTimestamp.string(date), null)
                pending.add(Pending(meta, TelemetryItem.Event(start), date))
            }
            pending.add(Pending(meta, clean, date))
            trim()
            // Persisted when new, and otherwise at most every 30 s (store writes are cheap but not free)
            if (step.isNew || date - sessionSavedAt >= SESSION_SAVE_INTERVAL_MILLIS) {
                sessionSavedAt = date
                toSave = step.session
            }
            if (remote == RemoteGate.ENABLED) {
                flushIn = when {
                    pending.size >= configuration.maxBatch -> 0
                    flushScheduled -> null
                    else -> configuration.flushDelayMillis
                }
            }
        }
        toSave?.save(store, configuration.sessionKey)
        flushIn?.let(::scheduleFlush)
    }

    /**
     * At most one per [intervalMillis] per [key]; the one sent carries `suppressed`, how many were dropped since. Returns whether it was recorded.
     */
    public fun recordThrottled(event: FaroEvent, key: String, intervalMillis: Long): Boolean {
        val date = now()
        val suppressed = synchronized(lock) {
            val entry = throttled[key]
            if (entry != null && date - entry.sentAt < intervalMillis) {
                entry.suppressed += 1
                return false
            }
            throttled[key] = Throttle(date, 0)
            entry?.suppressed ?: 0
        }
        record(TelemetryItem.Event(event.copy(attributes = (event.attributes ?: emptyMap()) + ("suppressed" to suppressed.toString()))))
        return true
    }

    private fun meta(session: TelemetrySession) = FaroMeta(
        app = configuration.app,
        sdk = configuration.sdk,
        session = FaroMeta.Session(session.id, mapOf("platform" to configuration.platform)),
        user = userId?.let { FaroMeta.User(it) },
        view = view?.let { FaroMeta.View(it) },
        browser = configuration.browser,
    )

    /** Drops the oldest beyond [Configuration.maxPending]; call inside the lock */
    private fun trim() {
        val extra = pending.size - configuration.maxPending
        if (extra > 0) pending.subList(0, extra).clear()
    }

    // endregion

    // region Sending

    private fun scheduleFlush(afterMillis: Long) {
        if (afterMillis > 0) {
            val first = synchronized(lock) { (!flushScheduled).also { flushScheduled = true } }
            if (!first) return
        }
        scope.launch {
            if (afterMillis > 0) {
                delay(afterMillis)
                synchronized(lock) { flushScheduled = false }
            }
            flush()
        }
    }

    /** The next batch, or null when there is nothing to send now; null also ends the running drain (atomically) */
    private fun takeBatch(): List<Pending>? {
        val date = now()
        return synchronized(lock) {
            if (!consent || remote != RemoteGate.ENABLED || sending || pending.isEmpty() || notBefore?.let { date < it } == true) {
                drain = null
                return null
            }
            sending = true
            val count = minOf(configuration.maxBatch, pending.size)
            val batch = pending.subList(0, count).toList()
            pending.subList(0, count).clear()
            batch
        }
    }

    /**
     * Sends everything queued while allowed. Returns false when a batch failed (it is queued again, with backoff). Only
     * one drain runs at a time: a call during another one waits for it and then sends what it left.
     */
    public suspend fun flush(): Boolean {
        while (true) {
            var mine = false
            val task = synchronized(lock) {
                // A completed drain is never joined: a dispatcher that ran it inline could finish it before it was recorded here
                drain?.takeUnless { it.isCompleted } ?: scope.async { drainQueue() }.also {
                    drain = it
                    mine = true
                }
            }
            val result = task.await()
            if (mine) return result
        }
    }

    private suspend fun drainQueue(): Boolean {
        while (true) {
            val batch = takeBatch() ?: return true
            val failed = ArrayList<Pending>()
            var retryAfter: Long? = null
            val groups = groupedByMeta(batch)
            for ((index, group) in groups.withIndex()) {
                val body = runCatching {
                    FaroPayload.json.encodeToString(FaroPayload.serializer(), FaroPayload.of(group[0].meta, group.map { it.item })).toByteArray()
                }.getOrNull() ?: continue
                try {
                    val response = transport.post(body)
                    when (response.status) {
                        in 200..299 -> Unit
                        // Overloaded or down: try again later
                        408, 429, in 500..599 -> {
                            failed += group
                            response.retryAfterSeconds?.let { retryAfter = it * 1000 }
                        }
                        // Refused (bad payload, gone): sending it again would not help
                        else -> Unit
                    }
                } catch (e: CancellationException) {
                    // The scope ended mid-drain: what was not sent goes back, without a backoff, and the drain is released
                    releaseCancelled(failed + groups.drop(index).flatten())
                    throw e
                } catch (_: Exception) {
                    failed += group
                }
            }
            val wait = finishBatch(failed, retryAfter)
            if (wait != null) {
                scheduleFlush(wait)
                return false
            }
        }
    }

    private fun releaseCancelled(unsent: List<Pending>) {
        synchronized(lock) {
            sending = false
            drain = null
            if (consent && remote != RemoteGate.DISABLED) {
                pending.addAll(0, unsent)
                trim()
            }
        }
    }

    /** Re-queues a failed batch ahead of newer items and returns how long to wait in ms, or null after a success */
    private fun finishBatch(failed: List<Pending>, retryAfterMillis: Long?): Long? {
        val date = now()
        return synchronized(lock) {
            sending = false
            if (failed.isEmpty()) {
                // the drain goes on: takeBatch ends it
                failures = 0
                notBefore = null
                return null
            }
            drain = null
            failures += 1
            val backoff = configuration.backoffMillis[minOf(failures - 1, configuration.backoffMillis.lastIndex)]
            val wait = maxOf(retryAfterMillis ?: 0, backoff)
            notBefore = date + wait
            if (consent && remote != RemoteGate.DISABLED) {
                pending.addAll(0, failed)
                trim()
            }
            wait
        }
    }

    /** Consecutive items with the same meta go in one request (a sign-in or a new session starts a new one) */
    internal fun groupedByMeta(items: List<Pending>): List<List<Pending>> {
        val groups = ArrayList<MutableList<Pending>>()
        for (item in items) {
            if (groups.lastOrNull()?.last()?.meta == item.meta) groups.last().add(item) else groups.add(mutableListOf(item))
        }
        return groups
    }

    // endregion

    // region Leaving the app and coming back

    /**
     * The app goes to the background (`onStop`): there is no grace period, so what is queued
     * is written first, then sent; the file is rewritten (or deleted) with whatever is left.
     */
    public suspend fun persistAndFlush() {
        synchronized(lock) { notBefore = null }
        persist()
        flush()
        persist()
    }

    /** [persistAndFlush] in the client's own scope (the facade's `onStop`) */
    public fun onStop() {
        scope.launch { persistAndFlush() }
    }

    /** Back in the foreground: the queue is still in memory, so the stored copy goes (no duplicates); send now */
    public fun foregrounded() {
        deleteStore()
        synchronized(lock) { notBefore = null }
        if (isOpen) scheduleFlush(0)
    }

    /** Writes what is still queued (oldest dropped beyond [Configuration.maxStoredBytes]); only while the user's setting allows it */
    public fun persist() {
        val file = storeFile ?: return
        val items = synchronized(lock) { if (consent && remote != RemoteGate.DISABLED) pending.toList() else emptyList() }
        if (items.isEmpty()) {
            deleteStore()
            return
        }
        val serializer = ListSerializer(Pending.serializer())
        var kept = items
        while (kept.isNotEmpty()) {
            val data = runCatching { FaroPayload.json.encodeToString(serializer, kept).toByteArray() }.getOrNull()
            if (data != null && data.size <= configuration.maxStoredBytes) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val temp = File(file.path + ".tmp")
                    temp.writeBytes(data)
                    if (!temp.renameTo(file)) {
                        // Some file systems do not rename over an existing file
                        check(file.delete() || !file.exists())
                        check(temp.renameTo(file))
                    }
                }
                return
            }
            kept = kept.drop(maxOf(1, kept.size / 2))
        }
        deleteStore()
    }

    /** At launch: what the last run could not send (younger than [Configuration.maxStoredAgeMillis]) goes ahead of new items; the file is removed */
    public fun restore() {
        val file = storeFile ?: return
        val data = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return
        deleteStore()
        val stored = runCatching { FaroPayload.json.decodeFromString(ListSerializer(Pending.serializer()), data.decodeToString()) }.getOrNull() ?: return
        val date = now()
        val fresh = stored.filter { date - it.recordedAt < configuration.maxStoredAgeMillis }
        synchronized(lock) {
            if (!consent || remote == RemoteGate.DISABLED) return
            pending.addAll(0, fresh)
            trim()
        }
    }

    private fun deleteStore() {
        val file = storeFile ?: return
        runCatching { file.delete() }
        runCatching { File(file.path + ".tmp").delete() }
    }

    // endregion

    private companion object {
        const val SESSION_SAVE_INTERVAL_MILLIS = 30_000L
    }
}
