package com.hainesy.karoogarage

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fired when the rider taps the data field. Same pipeline as the BonusAction. */
class OpenGarageAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        GarageRuntime.trigger(context)
    }
}

/**
 * Graphical, tappable in-ride field: shows the entity's live state and fires
 * the configured service call on tap. The trigger path for riders without a
 * SRAM AXS controller (#4), and the visual confirmation loop (#2).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class GarageDataType(extension: String) : DataTypeImpl(extension, "garage") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        runCatching { emitter.onNext(UpdateGraphicConfig(showHeader = false)) }
        GarageRuntime.onViewActive(context)

        // The host can die without delivering stopView; a failed binder call
        // is then the only teardown signal. Guarded so cancellable + failure
        // can't double-decrement the poller refcount.
        val tornDown = AtomicBoolean(false)
        val jobRef = AtomicReference<Job?>(null)
        fun tearDown() {
            if (tornDown.compareAndSet(false, true)) {
                GarageRuntime.onViewInactive()
                jobRef.get()?.cancel()
            }
        }

        val job = CoroutineScope(Dispatchers.Main).launch {
            GarageRuntime.fieldState.collectLatest { state ->
                if (!renderInto(context, config, emitter, state)) {
                    tearDown()
                    return@collectLatest
                }
                // The emitter drops updates arriving <900ms apart; re-render
                // once after the window so the latest state always lands.
                delay(1_100)
                if (!renderInto(context, config, emitter, state)) {
                    tearDown()
                }
            }
        }
        jobRef.set(job)
        if (tornDown.get()) job.cancel()

        emitter.setCancellable { tearDown() }
    }

    /** Returns false when the host-side binder is dead. */
    private suspend fun renderInto(
        context: Context,
        config: ViewConfig,
        emitter: ViewEmitter,
        state: GarageRuntime.FieldState,
    ): Boolean {
        val result = glance.compose(context, DpSize.Unspecified) {
            GarageTile(context, config, state)
        }
        return try {
            emitter.updateView(result.remoteViews)
            true
        } catch (e: RemoteException) {
            Log.w("GarageDataType", "host binder dead, tearing down view", e)
            false
        }
    }
}

@Composable
private fun GarageTile(
    context: Context,
    config: ViewConfig,
    state: GarageRuntime.FieldState,
) {
    val mainText = when {
        !state.configured -> context.getString(R.string.field_not_configured)
        state.authExpired -> context.getString(R.string.field_auth_expired)
        state.sending -> context.getString(R.string.field_sending)
        state.state == null -> "—"
        else -> state.state.uppercase()
    }
    val title = state.entityName ?: context.getString(R.string.app_name)
    val mainSize = minOf(config.textSize, 26)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF1B5E20)))
            .clickable(actionRunCallback<OpenGarageAction>())
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = TextStyle(
                    color = ColorProvider(Color(0xB3FFFFFF)),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            Text(
                text = mainText,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = mainSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            Text(
                text = context.getString(R.string.field_tap_hint),
                style = TextStyle(
                    color = ColorProvider(Color(0x80FFFFFF)),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

