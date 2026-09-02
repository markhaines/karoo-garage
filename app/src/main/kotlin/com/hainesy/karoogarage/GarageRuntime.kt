package com.hainesy.karoogarage

import android.content.Context
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the Karoo connection and the trigger pipeline, shared
 * by the BonusAction (extension service), the tappable data field (Glance
 * ActionCallback), and the state poller. One KarooSystemService, one config
 * store, one code path for "open the garage".
 *
 * Threading: everything long-running goes through [Components.scope]
 * (Dispatchers.Default — EncryptedSharedPreferences decryption must stay off
 * the main thread). All [fieldStateFlow] writes use the CAS-based update {}
 * because trigger() runs on binder/Glance threads concurrently with the
 * poller. All state refreshes are serialized through the single [pollJob] so
 * an in-flight slow poll can never clobber a fresher result.
 */
object GarageRuntime {

    /** What the in-ride data field renders. */
    data class FieldState(
        val configured: Boolean = false,
        val entityName: String? = null,
        /** Raw HA state ("open", "closed", "opening", …), null = not fetched. */
        val state: String? = null,
        val sending: Boolean = false,
        val authExpired: Boolean = false,
    )

    private val fieldStateFlow = MutableStateFlow(FieldState())
    val fieldState: StateFlow<FieldState> get() = fieldStateFlow

    private class Components(context: Context) {
        val appContext: Context = context.applicationContext
        val karooSystem = KarooSystemService(appContext).also { service ->
            service.connect { connected ->
                Log.d(TAG, "Karoo system connected=$connected")
            }
        }
        val configStore = ConfigStore(appContext)
        val http = KarooHttp(karooSystem)
        val authClient = AuthClient(http)
        val haClient = HomeAssistantClient(http, authClient, configStore)
        val repository = EntityRepository(http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Declared last: it needs scope and the clients above to already exist.
        val batteryReporter = BatteryReporter(appContext, karooSystem, http, configStore, scope)
    }

    @Volatile
    private var components: Components? = null

    @Synchronized
    private fun components(context: Context): Components =
        components ?: Components(context).also { components = it }

    /**
     * Called from GarageExtension.onCreate: starts the Karoo bind at service
     * creation so the first alert isn't dispatched into an unconnected
     * KarooSystemService (dispatch() silently drops effects until bound).
     */
    fun warm(context: Context) {
        components(context).batteryReporter.start()
    }

    /**
     * Called from GarageExtension.onDestroy: the host is going away, and
     * stopView is not guaranteed to have been delivered for live views.
     */
    @Synchronized
    fun reset() {
        activeViews = 0
        pollJob?.cancel()
        pollJob = null
        components?.batteryReporter?.stop()
    }

    /** Settings buttons: "Send now" (kind=report) and "Dump devices" (kind=dump). */
    suspend fun sendBatteryReportNow(context: Context, kind: String): Result<Unit> =
        components(context).batteryReporter.sendNow(kind)

    // ---- Trigger (BonusAction + field tap) ----

    @Volatile
    private var lastTriggerAt = 0L

    fun trigger(context: Context) {
        val c = components(context)

        // BLE-tunnelled commands can take 20s+ to arrive; a retry meanwhile
        // becomes a second toggle that reverses the first (seen in the wild:
        // door opened, then re-closed by the impatient second press).
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerAt < DEBOUNCE_MS) {
                dispatchAlert(
                    c,
                    title = c.appContext.getString(R.string.alert_debounced_title),
                    detail = c.appContext.getString(R.string.alert_debounced_detail),
                    isError = false,
                )
                return
            }
            lastTriggerAt = now
        }

        val state = c.configStore.load()
        if (state == null || !state.isValid()) {
            dispatchAlert(
                c,
                title = c.appContext.getString(R.string.alert_not_configured_title),
                detail = c.appContext.getString(R.string.alert_not_configured_detail),
                isError = true,
            )
            return
        }

        dispatchAlert(
            c,
            title = c.appContext.getString(R.string.alert_triggered_title),
            detail = c.appContext.getString(R.string.alert_triggered_detail),
            isError = false,
            autoDismissMs = 2_000L,
        )
        fieldStateFlow.update { it.copy(sending = true) }

        c.scope.launch {
            c.haClient.trigger()
                .onFailure { error ->
                    Log.w(TAG, "HA call failed", error)
                    val detail = when {
                        error is ReauthRequiredException ->
                            c.appContext.getString(R.string.alert_reauth_detail)
                        error.message?.contains("timed out") == true ->
                            c.appContext.getString(R.string.alert_timeout_detail)
                        else -> error.message
                            ?: c.appContext.getString(R.string.alert_failed_detail_fallback)
                    }
                    dispatchAlert(
                        c,
                        title = c.appContext.getString(R.string.alert_failed_title),
                        detail = detail,
                        isError = true,
                    )
                    fieldStateFlow.update {
                        it.copy(
                            sending = false,
                            authExpired = error is ReauthRequiredException,
                        )
                    }
                }
                .onSuccess {
                    fieldStateFlow.update { it.copy(sending = false) }
                    // Catch the door's transition (opening -> open) quickly.
                    restartPolling(c, burst = true)
                }
        }
    }

    private fun dispatchAlert(
        c: Components,
        title: String,
        detail: String,
        isError: Boolean,
        autoDismissMs: Long? = 4_000L,
    ) {
        val alert = InRideAlert(
            id = "garage-${if (isError) "error" else "ok"}",
            icon = R.drawable.ic_garage,
            title = title,
            detail = detail,
            autoDismissMs = autoDismissMs,
            backgroundColor = if (isError) R.color.alert_bg_error else R.color.alert_bg_success,
            textColor = R.color.alert_text,
        )
        if (c.karooSystem.dispatch(alert)) return
        // Not bound yet (cold start, or a reconnect window) — dispatch drops
        // effects silently, so retry briefly instead of losing the alert.
        c.scope.launch {
            val deadline = System.currentTimeMillis() + ALERT_RETRY_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                delay(150)
                if (c.karooSystem.dispatch(alert)) return@launch
            }
            Log.w(TAG, "dropped alert '$title' — Karoo system never connected")
        }
    }

    // ---- State polling (data field) ----

    private var activeViews = 0
    private var pollJob: Job? = null

    @Synchronized
    fun onViewActive(context: Context) {
        val c = components(context)
        activeViews++
        if (pollJob?.isActive != true) {
            startPolling(c, burst = false)
        }
    }

    @Synchronized
    fun onViewInactive() {
        activeViews = (activeViews - 1).coerceAtLeast(0)
        if (activeViews == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /**
     * All refreshes run inside the single poll job: restarting it cancels any
     * in-flight request, so results always apply in request order.
     */
    @Synchronized
    private fun restartPolling(c: Components, burst: Boolean) {
        if (activeViews == 0) return
        pollJob?.cancel()
        startPolling(c, burst)
    }

    private fun startPolling(c: Components, burst: Boolean) {
        pollJob = c.scope.launch {
            if (burst) {
                for (delayMs in listOf(2_000L, 3_000L, 5_000L, 10_000L, 10_000L)) {
                    delay(delayMs)
                    refreshState(c)
                }
            } else {
                refreshState(c)
            }
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refreshState(c)
            }
        }
    }

    private suspend fun refreshState(c: Components) {
        val config = c.configStore.load()
        if (config == null || !config.isValid() || config.entityId.isBlank()) {
            fieldStateFlow.update {
                it.copy(configured = false, entityName = null, state = null)
            }
            return
        }

        val token = when (config.authMode) {
            AuthMode.LEGACY_TOKEN -> Result.success(config.token!!)
            AuthMode.OAUTH -> c.haClient.freshAccessToken(config)
        }.getOrElse { error ->
            fieldStateFlow.update {
                it.copy(
                    configured = true,
                    entityName = config.entityName,
                    authExpired = error is ReauthRequiredException,
                )
            }
            return
        }

        c.repository.fetchState(config.baseUrl, token, config.entityId)
            .onSuccess { state ->
                fieldStateFlow.update {
                    it.copy(
                        configured = true,
                        entityName = config.entityName,
                        state = state,
                        authExpired = false,
                    )
                }
            }
            .onFailure { error ->
                // Transient (BLE timeouts are routine): keep last-known state.
                Log.w(TAG, "state poll failed", error)
                fieldStateFlow.update {
                    it.copy(configured = true, entityName = config.entityName)
                }
            }
    }

    private const val TAG = "GarageRuntime"

    /** Small GET every 20s while a field is on-screen — kind to the BLE bridge. */
    private const val POLL_INTERVAL_MS = 20_000L
    private const val ALERT_RETRY_WINDOW_MS = 5_000L

    /** Ignore re-presses while a command may still be in transit. */
    private const val DEBOUNCE_MS = 20_000L
}
