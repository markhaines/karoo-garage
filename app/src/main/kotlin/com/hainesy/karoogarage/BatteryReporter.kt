package com.hainesy.karoogarage

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports battery state to Home Assistant: the Karoo's own cell, plus the
 * coarse last-known battery of every sensor the Karoo has paired with
 * ([SavedDevices]). The Karoo is the only thing in the house that can hear the
 * AXS shifters (they never advertise to a passive scanner) so it is the data
 * SOURCE here, not a display.
 *
 * Transport is a Home Assistant webhook: one unauthenticated POST per report,
 * no bearer token, no refresh path that can silently die. The serial-to-entity
 * mapping lives in the HA automation that receives it, so it can change
 * without a Karoo release. Payloads are raw facts; interpretation is HA's job.
 *
 * Triggers: ride end (any non-Idle -> Idle, since real endings are usually
 * Paused -> Idle), a change in any paired device's battery, a 30-minute tick
 * while idle, and the two manual buttons in Settings. Sends are coalesced and
 * NEVER queued: a dropped report costs nothing because the next one carries
 * full current state.
 *
 * "dump" is a report with the extra diagnostics that decide what the rest of
 * the pipeline can do: whether the Karoo streams its battery off-ride, and the
 * raw SavedDevices tree including each device's components map. It goes to
 * logcat in chunks as well as to HA, because logcat truncates near 4 KB.
 */
class BatteryReporter(
    private val appContext: Context,
    private val karooSystem: KarooSystemService,
    private val http: KarooHttp,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
) {
    @Volatile private var latestDevices: SavedDevices? = null
    @Volatile private var lastRideState: RideState? = null
    @Volatile private var lastFingerprint: String? = null
    @Volatile private var lastSentAt = 0L
    private val inFlight = AtomicBoolean(false)

    private var rideConsumerId: String? = null
    private var devicesConsumerId: String? = null
    private var tickJob: Job? = null
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        rideConsumerId = karooSystem.addConsumer<RideState> { state ->
            val previous = lastRideState
            lastRideState = state
            if (previous != null && previous !is RideState.Idle && state is RideState.Idle) {
                requestSend("ride_end")
            }
        }
        devicesConsumerId = karooSystem.addConsumer<SavedDevices> { event ->
            latestDevices = event
            val fp = fingerprint(event)
            if (fp != lastFingerprint) {
                lastFingerprint = fp
                requestSend("devices_changed")
            }
        }
        // A plain coroutine delay holds no wakelock: while the Karoo sleeps this
        // simply does not run, and fires once after wake with the time elapsed.
        // No WorkManager or AlarmManager on purpose; those would wake the device.
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val idle = lastRideState == null || lastRideState is RideState.Idle
                if (idle && !lowBatteryNotCharging()) requestSend("tick")
            }
        }
        Log.i(TAG, "started")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        rideConsumerId?.let { karooSystem.removeConsumer(it) }
        devicesConsumerId?.let { karooSystem.removeConsumer(it) }
        rideConsumerId = null
        devicesConsumerId = null
        tickJob?.cancel()
        tickJob = null
        Log.i(TAG, "stopped")
    }

    /** Automatic triggers: coalesced, rate-limited, dropped if one is in flight. */
    fun requestSend(reason: String) {
        val now = System.currentTimeMillis()
        if (inFlight.get()) return
        if (now - lastSentAt < MIN_GAP_MS) return
        scope.launch { send(reason = reason, kind = "report", manual = false) }
    }

    /** Settings buttons: always attempt, even when automatic reporting is off. */
    suspend fun sendNow(kind: String): Result<Unit> = send(reason = "manual", kind = kind, manual = true)

    private suspend fun send(reason: String, kind: String, manual: Boolean): Result<Unit> {
        if (!inFlight.compareAndSet(false, true)) {
            return Result.failure(IOException("a report is already in flight"))
        }
        try {
            val settings = configStore.loadBatteryReport()
            val baseUrl = configStore.load()?.baseUrl
            if (!manual && !settings.enabled) return Result.failure(IOException("reporting disabled"))
            if (settings.webhookId.isBlank() || baseUrl.isNullOrBlank()) {
                return Result.failure(IOException("webhook not configured"))
            }

            val payload = buildPayload(reason, kind, diagnostics = kind == "dump")
            val json = payload.toString()
            if (kind == "dump") {
                json.chunked(LOG_CHUNK).forEachIndexed { i, s -> Log.i(TAG, "dump[$i] $s") }
            }

            val url = "$baseUrl/api/webhook/${settings.webhookId}"
            return http.postJson(
                url = url,
                json = json,
                timeoutMs = SEND_TIMEOUT_MS,
                waitForConnection = false,
            ).mapCatching { response ->
                if (!response.isSuccess) throw HttpStatusException(response.statusCode)
                lastSentAt = System.currentTimeMillis()
                configStore.saveBatteryLastSent(lastSentAt)
                Log.i(TAG, "sent $kind ($reason), ${json.length} bytes")
                Unit
            }.onFailure { error ->
                Log.w(TAG, "report ($reason) failed: ${error.message}")
            }
        } finally {
            inFlight.set(false)
        }
    }

    // ---- payload ----

    private suspend fun buildPayload(reason: String, kind: String, diagnostics: Boolean): JsonObject {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        // The stream is informational: the dump decides whether it ever streams
        // off-ride. BatteryManager above is the source of truth either way.
        val (streamPct, streamState) = probeBatteryStream(if (diagnostics) 10_000L else 3_000L)
        val devices = awaitDevices(if (diagnostics) 5_000L else 1_500L)

        return buildJsonObject {
            put("kind", kind)
            put("reason", reason)
            put("ext_version", BuildConfig.VERSION_NAME)
            put("sent_at", System.currentTimeMillis())
            putJsonObject("karoo") {
                put("serial", karooSystem.serial)
                put("battery_pct", pct)
                put("charging", charging)
                put("stream_pct", streamPct)
                put("stream_state", streamState)
            }
            put("ride_state", rideStateName(lastRideState))
            putJsonArray("devices") {
                devices?.devices?.forEach { add(deviceJson(it)) }
            }
            if (diagnostics && devices != null) {
                // The SDK's own serialisation, untouched, so nothing the shaped
                // list above might drop is lost while the mapping is being decided.
                put("raw", Json.encodeToJsonElement(SavedDevices.serializer(), devices))
            }
        }
    }

    private fun deviceJson(d: SavedDevices.SavedDevice): JsonObject = buildJsonObject {
        put("id", d.id)
        put("name", d.name)
        put("connection", d.connectionType)
        put("enabled", d.enabled)
        put("manufacturer", d.details.manufacturer)
        put("serial", d.details.serialNumber)
        put("battery", d.details.lastBattery?.name)
        put("battery_at", d.details.lastBatteryUpdate)
        putJsonArray("supported") { d.supportedDataTypes.forEach { add(it) } }
        d.components?.let { comps ->
            putJsonObject("components") {
                comps.forEach { (key, c) ->
                    putJsonObject(key) {
                        put("battery", c.lastBattery?.name)
                        put("battery_at", c.lastBatteryUpdate)
                        put("manufacturer", c.manufacturer)
                        put("serial", c.serialNumber)
                    }
                }
            }
        }
    }

    /** Uses the cached list if a SavedDevices event has arrived, else asks once. */
    private suspend fun awaitDevices(timeoutMs: Long): SavedDevices? {
        latestDevices?.let { return it }
        val deferred = CompletableDeferred<SavedDevices>()
        val id = karooSystem.addConsumer<SavedDevices> { event ->
            latestDevices = event
            deferred.complete(event)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            null
        } finally {
            karooSystem.removeConsumer(id)
        }
    }

    /** First Streaming value, or the last non-streaming state seen before timeout. */
    private suspend fun probeBatteryStream(timeoutMs: Long): Pair<Double?, String> {
        val deferred = CompletableDeferred<Pair<Double?, String>>()
        var lastSeen = "none"
        val id = karooSystem.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.BATTERY_PERCENT),
        ) { event ->
            when (val s = event.state) {
                is StreamState.Streaming -> deferred.complete(s.dataPoint.singleValue to "Streaming")
                is StreamState.NotAvailable -> deferred.complete(null to "NotAvailable")
                is StreamState.Idle -> lastSeen = "Idle"
                is StreamState.Searching -> lastSeen = "Searching"
            }
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            null to "timeout($lastSeen)"
        } finally {
            karooSystem.removeConsumer(id)
        }
    }

    private fun lowBatteryNotCharging(): Boolean {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < LOW_BATTERY_PCT && !bm.isCharging
    }

    private fun fingerprint(event: SavedDevices): String = event.devices.joinToString("|") { d ->
        val comps = d.components?.entries?.joinToString(",") {
            "${it.key}=${it.value.lastBattery}:${it.value.lastBatteryUpdate}"
        }.orEmpty()
        "${d.id}:${d.details.lastBattery}:${d.details.lastBatteryUpdate}:$comps"
    }

    private fun rideStateName(s: RideState?): String = when (s) {
        is RideState.Idle -> "Idle"
        is RideState.Recording -> "Recording"
        is RideState.Paused -> "Paused"
        null -> "Unknown"
    }

    companion object {
        private const val TAG = "BatteryReporter"
        private const val TICK_MS = 30L * 60L * 1000L
        private const val MIN_GAP_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 15_000L
        private const val LOW_BATTERY_PCT = 15
        private const val LOG_CHUNK = 3000
    }
}
