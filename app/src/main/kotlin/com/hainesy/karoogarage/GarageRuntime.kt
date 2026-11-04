package com.hainesy.karoogarage

import android.content.Context
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * the main thread). That scope belongs to [lifecycle]: [reset] cancels it,
 * disconnects the Karoo service and drops the components, and the next use
 * builds fresh ones (#7). The [commandGate] deliberately outlives a reset: a
 * command cancelled locally may still be on its way to Home Assistant (#5).
 *
 * All [fieldStateFlow] writes use the CAS-based update {}
 * because trigger() runs on binder/Glance threads concurrently with the
 * poller. All state refreshes are serialized through the single [pollJob] so
 * an in-flight slow poll can never clobber a fresher result.
 */
object GarageRuntime {

    /** Where the last command is, as far as the data field is concerned. */
    enum class CommandPhase { IDLE, SENDING, TIMED_OUT }

    /** What the in-ride data field renders. */
    data class FieldState(
        val configured: Boolean = false,
        val entityName: String? = null,
        /** Raw HA state ("open", "closed", "opening", …), null = not fetched. */
        val state: String? = null,
        val command: CommandPhase = CommandPhase.IDLE,
        val authExpired: Boolean = false,
    )

    private val fieldStateFlow = MutableStateFlow(FieldState())
    val fieldState: StateFlow<FieldState> get() = fieldStateFlow

    private class Components(context: Context, val scope: CoroutineScope) {
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
        // Declared last: it needs scope and the clients above to already exist.
        val batteryReporter = BatteryReporter(appContext, karooSystem, http, configStore, scope)
    }

    private val lifecycle = RuntimeLifecycle<Components>(close = { c ->
        c.batteryReporter.stop()
        // Throws if the bind never happened; there is nothing left to release then.
        runCatching { c.karooSystem.disconnect() }
            .onFailure { Log.w(TAG, "disconnect failed: ${it.javaClass.simpleName}") }
    })

    private fun components(context: Context): Components =
        lifecycle.get { scope -> Components(context, scope) }.value

    private val commandGate = CommandGate(cooldownMs = DEBOUNCE_MS)
    private val commandRunner = CommandRunner(commandGate)

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
     * Cancels every runtime job (trigger, poll, alert retry, battery report),
     * disconnects from the Karoo system and drops the components; a later
     * extension instance gets fresh ones. Safe mid-trigger: the command gate
     * treats the cancelled request as finished and starts its cooldown.
     */
    @Synchronized
    fun reset() {
        activeViews = 0
        pollJob = null
        lifecycle.reset()
        fieldStateFlow.value = FieldState()
    }

    /** Settings buttons: "Send now" (kind=report) and "Dump devices" (kind=dump). */
    suspend fun sendBatteryReportNow(context: Context, kind: String): Result<Unit> =
        components(context).batteryReporter.sendNow(kind)

    /**
     * Best-effort server-side revocation of an OAuth session the app has already dropped
     * locally (a .kgcfg import replaced it). Runs on the runtime scope so it outlives the
     * short-lived import activity (a runtime teardown cancels it). Failures are logged
     * without the token and otherwise ignored; the session still shows in the HA profile
     * for manual revocation.
     */
    fun revokeBestEffort(context: Context, session: ConfigImport.Session) {
        val c = components(context)
        c.scope.launch {
            c.authClient.revoke(session.baseUrl, session.refreshToken)
                .onFailure { Log.w(TAG, "revoking replaced OAuth session failed: ${it.javaClass.simpleName}") }
        }
    }

    // ---- Trigger (BonusAction + field tap) ----

    fun trigger(context: Context) {
        val c = components(context)
        commandRunner.run(
            scope = c.scope,
            // Checked before the gate, so an unconfigured press never starts the cooldown.
            isConfigured = { c.configStore.load()?.isValid() == true },
            send = { c.haClient.trigger() },
        ) { event -> onCommandEvent(c, event) }
            // Torn down mid-request: no terminal event comes, so don't leave a
            // recreated field stuck on SENDING.
            ?.invokeOnCompletion { cause ->
                if (cause != null) {
                    fieldStateFlow.update {
                        if (it.command == CommandPhase.SENDING) it.copy(command = CommandPhase.IDLE) else it
                    }
                }
            }
    }

    private fun onCommandEvent(c: Components, event: CommandRunner.Event) {
        val ctx = c.appContext
        when (event) {
            CommandRunner.Event.NotConfigured -> dispatchAlert(
                c,
                title = ctx.getString(R.string.alert_not_configured_title),
                detail = ctx.getString(R.string.alert_not_configured_detail),
                isError = true,
            )
            CommandRunner.Event.StillSending -> dispatchAlert(
                c,
                title = ctx.getString(R.string.alert_still_sending_title),
                detail = ctx.getString(R.string.alert_still_sending_detail),
                isError = false,
            )
            CommandRunner.Event.CoolingDown -> dispatchAlert(
                c,
                title = ctx.getString(R.string.alert_debounced_title),
                detail = ctx.getString(R.string.alert_debounced_detail),
                isError = false,
            )
            CommandRunner.Event.Sending -> {
                dispatchAlert(
                    c,
                    title = ctx.getString(R.string.alert_triggered_title),
                    detail = ctx.getString(R.string.alert_triggered_detail),
                    isError = false,
                    autoDismissMs = 2_000L,
                )
                fieldStateFlow.update { it.copy(command = CommandPhase.SENDING) }
            }
            CommandRunner.Event.Done -> {
                dispatchAlert(
                    c,
                    title = ctx.getString(R.string.alert_triggered_title),
                    detail = ctx.getString(R.string.alert_done_detail),
                    isError = false,
                    autoDismissMs = 2_000L,
                )
                fieldStateFlow.update { it.copy(command = CommandPhase.IDLE, authExpired = false) }
                // Catch the door's transition (opening -> open) quickly.
                restartPolling(c, burst = true)
            }
            CommandRunner.Event.TimedOut -> {
                Log.w(TAG, "HA call timed out")
                dispatchAlert(
                    c,
                    title = ctx.getString(R.string.alert_failed_title),
                    detail = ctx.getString(R.string.alert_timeout_detail),
                    isError = true,
                )
                fieldStateFlow.update { it.copy(command = CommandPhase.TIMED_OUT) }
                // It may still land: watch for the door moving, and drop the
                // "no reply" marker once the cooldown is over.
                restartPolling(c, burst = true)
                c.scope.launch {
                    delay(DEBOUNCE_MS)
                    fieldStateFlow.update {
                        if (it.command == CommandPhase.TIMED_OUT) it.copy(command = CommandPhase.IDLE) else it
                    }
                }
            }
            is CommandRunner.Event.Failed -> {
                val error = event.error
                Log.w(TAG, "HA call failed: ${error.javaClass.simpleName}")
                val detail = if (error is ReauthRequiredException) {
                    ctx.getString(R.string.alert_reauth_detail)
                } else {
                    error.message ?: ctx.getString(R.string.alert_failed_detail_fallback)
                }
                dispatchAlert(
                    c,
                    title = ctx.getString(R.string.alert_failed_title),
                    detail = detail,
                    isError = true,
                )
                fieldStateFlow.update {
                    it.copy(
                        command = CommandPhase.IDLE,
                        authExpired = error is ReauthRequiredException,
                    )
                }
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

    /**
     * Cooldown after a command resolves (done, failed, timed out or torn
     * down). Presses while one is still in flight are refused outright.
     */
    private const val DEBOUNCE_MS = 20_000L
}
