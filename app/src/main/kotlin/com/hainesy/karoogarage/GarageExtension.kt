package com.hainesy.karoogarage

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GarageExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private val karooSystem by lazy { KarooSystemService(this) }
    private val configStore by lazy { ConfigStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            Log.d(TAG, "Karoo system connected=$connected")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        Log.d(TAG, "onBonusAction actionId=$actionId")
        when (actionId) {
            ACTION_OPEN_GARAGE -> handleOpenGarage()
            else -> Log.w(TAG, "Unknown actionId=$actionId")
        }
    }

    private fun handleOpenGarage() {
        val config = configStore.load()
        if (config == null || !config.isValid()) {
            dispatchAlert(
                title = getString(R.string.alert_not_configured_title),
                detail = getString(R.string.alert_not_configured_detail),
                isError = true,
            )
            return
        }

        dispatchAlert(
            title = getString(R.string.alert_triggered_title),
            detail = getString(R.string.alert_triggered_detail),
            isError = false,
            autoDismissMs = 2_000L,
        )

        scope.launch {
            HomeAssistantClient(config).trigger()
                .onFailure { error ->
                    Log.w(TAG, "HA call failed", error)
                    dispatchAlert(
                        title = getString(R.string.alert_failed_title),
                        detail = error.message ?: getString(R.string.alert_failed_detail_fallback),
                        isError = true,
                    )
                }
        }
    }

    private fun dispatchAlert(
        title: String,
        detail: String,
        isError: Boolean,
        autoDismissMs: Long? = 4_000L,
    ) {
        karooSystem.dispatch(
            InRideAlert(
                id = "garage-${if (isError) "error" else "ok"}",
                icon = R.drawable.ic_garage,
                title = title,
                detail = detail,
                autoDismissMs = autoDismissMs,
                backgroundColor = if (isError) R.color.alert_bg_error else R.color.alert_bg_success,
                textColor = R.color.alert_text,
            ),
        )
    }

    companion object {
        private const val TAG = "GarageExtension"
        private const val EXTENSION_ID = "garage"
        private const val ACTION_OPEN_GARAGE = "open-garage"
    }
}
