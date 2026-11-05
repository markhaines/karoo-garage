package com.hainesy.karoogarage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns one set of process-wide runtime resources and the coroutine scope all their work runs
 * in (#7). [get] creates them on first use; [reset] cancels every job in the scope (trigger,
 * poll, alert retry, battery report), then [close]s the resources (the Karoo disconnect), and
 * forgets them, so the next [get] builds a fresh set rather than reusing a disconnected one.
 *
 * The scope has its own [SupervisorJob]: one failed job does not take the others down, and
 * cancelling it reaches everything launched in it, whichever thread launched it.
 */
class RuntimeLifecycle<T : Any>(
    private val close: (T) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    class Live<T>(val value: T, val scope: CoroutineScope)

    private var live: Live<T>? = null

    @Synchronized
    fun get(create: (CoroutineScope) -> T): Live<T> =
        live ?: run {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val value = try {
                create(scope)
            } catch (e: Throwable) {
                scope.cancel()
                throw e
            }
            Live(value, scope).also { live = it }
        }

    @get:Synchronized
    val current: Live<T>? get() = live

    /** Safe to call at any time, any number of times. */
    @Synchronized
    fun reset() {
        val old = live ?: return
        live = null
        old.scope.cancel("runtime torn down")
        close(old.value)
    }
}
