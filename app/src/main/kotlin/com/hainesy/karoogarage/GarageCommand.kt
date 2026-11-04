package com.hainesy.karoogarage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/** Our own timeout fired. The request may still be in transit and land later. */
class RequestTimeoutException(message: String) : IOException(message)

/**
 * One garage command at a time (#5). A `toggle` that is still in flight, or that only just
 * finished, must never be followed by a second one: over the Bluetooth tunnel a request can
 * take 20s+, and a second toggle landing after the first reverses the door.
 *
 * The gate is busy from [tryStart] until [finish], and then cools down for [cooldownMs]
 * measured from that terminal event, not from the press. Callers check configuration before
 * [tryStart], so an unconfigured press never consumes the gate.
 */
class CommandGate(
    private val cooldownMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Decision { START, IN_FLIGHT, COOLING_DOWN }

    private var inFlight = false
    private var coolUntil = Long.MIN_VALUE

    @Synchronized
    fun tryStart(): Decision = when {
        inFlight -> Decision.IN_FLIGHT
        clock() < coolUntil -> Decision.COOLING_DOWN
        else -> {
            inFlight = true
            Decision.START
        }
    }

    /** The command reached a terminal outcome. Idempotent. */
    @Synchronized
    fun finish() {
        if (!inFlight) return
        inFlight = false
        coolUntil = clock() + cooldownMs
    }

    @get:Synchronized
    val isInFlight: Boolean get() = inFlight
}

/**
 * Runs a garage command through a [CommandGate] and reports what happened, so the alert and the
 * data field can tell sending, timeout, completion and debounce apart.
 */
class CommandRunner(private val gate: CommandGate) {

    sealed class Event {
        /** Not set up. The gate was not touched. */
        object NotConfigured : Event()

        /** Refused: the previous command has not resolved yet. */
        object StillSending : Event()

        /** Refused: the previous command resolved less than the cooldown ago. */
        object CoolingDown : Event()

        object Sending : Event()
        object Done : Event()

        /** Our timeout fired; the command may still arrive. */
        object TimedOut : Event()
        data class Failed(val error: Throwable) : Event()
    }

    /**
     * Starts [send] in [scope] unless the gate refuses. Every press produces exactly one
     * immediate event (NotConfigured, StillSending, CoolingDown or Sending); a started command
     * then produces one terminal event (Done, TimedOut or Failed), unless the runtime is torn
     * down mid-request, which reports nothing because the listeners are going away too. The gate
     * is released when the job completes for any reason, including cancellation before it ever
     * ran, so a torn-down request still starts the cooldown: it may yet reach Home Assistant.
     * Returns the started job, or null if the press was refused.
     */
    fun run(
        scope: CoroutineScope,
        isConfigured: () -> Boolean,
        send: suspend () -> Result<Unit>,
        onEvent: (Event) -> Unit,
    ): Job? {
        if (!isConfigured()) {
            onEvent(Event.NotConfigured)
            return null
        }
        when (gate.tryStart()) {
            CommandGate.Decision.IN_FLIGHT -> { onEvent(Event.StillSending); return null }
            CommandGate.Decision.COOLING_DOWN -> { onEvent(Event.CoolingDown); return null }
            CommandGate.Decision.START -> onEvent(Event.Sending)
        }
        val job = try {
            scope.launch {
                val result = try {
                    send()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                // Release before reporting, so a press that reacts to the event sees the cooldown.
                gate.finish()
                onEvent(
                    result.fold(
                        onSuccess = { Event.Done },
                        onFailure = { if (it is RequestTimeoutException) Event.TimedOut else Event.Failed(it) },
                    ),
                )
            }
        } catch (e: Throwable) {
            gate.finish()
            throw e
        }
        job.invokeOnCompletion { gate.finish() }
        return job
    }
}
