package com.hainesy.karoogarage

import android.util.Log
import io.hammerhead.karooext.extension.KarooExtension

class GarageExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    override val types by lazy {
        listOf(GarageDataType(EXTENSION_ID))
    }

    override fun onCreate() {
        super.onCreate()
        // Bind to the Karoo system now, not lazily on the first press —
        // dispatch() silently drops alerts until the connection is up.
        GarageRuntime.warm(this)
    }

    override fun onDestroy() {
        // stopView isn't guaranteed on host teardown; drop any leaked view
        // refcounts so the state poller can't run on with no views.
        GarageRuntime.reset()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        Log.d(TAG, "onBonusAction actionId=$actionId")
        when (actionId) {
            ACTION_OPEN_GARAGE -> GarageRuntime.trigger(this)
            else -> Log.w(TAG, "Unknown actionId=$actionId")
        }
    }

    companion object {
        private const val TAG = "GarageExtension"
        private const val EXTENSION_ID = "garage"
        private const val ACTION_OPEN_GARAGE = "open-garage"
    }
}
