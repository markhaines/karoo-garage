package com.hainesy.karoogarage

import com.hainesy.karoogarage.CommandRunner.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime teardown (#7). The fake stands in for the Karoo connection: it counts binds and
 * unbinds, and its "request" is a suspend delay. Nothing touches a network or device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeLifecycleTest {

    /** Stands in for GarageRuntime.Components: one Karoo bind per instance. */
    private class FakeComponents(val scope: CoroutineScope, private val connections: IntArray) {
        var disconnected = false
        init { connections[0]++ }
        fun disconnect() { check(!disconnected) { "double disconnect" }; disconnected = true; connections[0]-- }
    }

    private class Harness(test: TestScope) {
        val connections = IntArray(1)
        var creates = 0
        val lifecycle = RuntimeLifecycle<FakeComponents>(
            close = { it.disconnect() },
            dispatcher = StandardTestDispatcher(test.testScheduler),
        )
        fun get() = lifecycle.get { scope -> creates++; FakeComponents(scope, connections) }.value
    }

    @Test
    fun `components are created once and reused until reset`() = runTest {
        val h = Harness(this)
        assertSame(h.get(), h.get())
        assertEquals(1, h.creates)
        assertEquals(1, h.connections[0])
    }

    @Test
    fun `reset cancels every job, disconnects once, and clears the cache`() = runTest {
        val h = Harness(this)
        val c = h.get()
        // Finite stand-ins, so a regression fails the assertions instead of spinning the scheduler.
        val poll = c.scope.launch { repeat(100) { delay(20_000) } }
        val alertRetry = c.scope.launch { repeat(30) { delay(150) } }
        val battery = c.scope.launch { delay(30 * 60_000L) }
        runCurrent()

        h.lifecycle.reset()
        runCurrent()
        assertTrue(poll.isCancelled && alertRetry.isCancelled && battery.isCancelled)
        assertTrue(c.disconnected)
        assertEquals(0, h.connections[0])
        assertNull(h.lifecycle.current)

        h.lifecycle.reset() // repeated destroy is a no-op, not a double disconnect
        assertEquals(0, h.connections[0])
    }

    @Test
    fun `a recreated runtime is fresh, never the disconnected one`() = runTest {
        val h = Harness(this)
        val first = h.get()
        h.lifecycle.reset()
        val second = h.get()
        assertNotSame(first, second)
        assertFalse(second.disconnected)
        assertTrue(second.scope.coroutineContext[Job]!!.isActive)
        assertFalse(first.scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `repeated create and destroy cycles leave one connection and no orphan jobs`() = runTest {
        val h = Harness(this)
        val jobs = mutableListOf<Job>()
        repeat(25) {
            val c = h.get()
            h.get() // a second user in the same lifetime shares it
            jobs += c.scope.launch { repeat(100) { delay(1_000) } }
            advanceTimeBy(500)
            assertEquals(1, h.connections[0])
            h.lifecycle.reset()
        }
        h.get()
        runCurrent()
        assertEquals(1, h.connections[0])
        assertEquals(26, h.creates)
        assertTrue(jobs.all { it.isCancelled })
    }

    @Test
    fun `one failing job does not cancel the rest of the runtime`() = runTest {
        val h = Harness(this)
        val c = h.get()
        val survivor = c.scope.launch { delay(10_000) }
        c.scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }) { error("boom") }
        runCurrent()
        assertTrue(survivor.isActive)
        h.lifecycle.reset()
    }

    @Test
    fun `a failed create caches nothing and the next use retries`() = runTest {
        val h = Harness(this)
        var fail = true
        try {
            h.lifecycle.get { if (fail) error("bind failed") else FakeComponents(it, h.connections) }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertNull(h.lifecycle.current)
        fail = false
        h.lifecycle.get { if (fail) error("bind failed") else FakeComponents(it, h.connections) }
        assertEquals(1, h.connections[0])
    }

    // --- #5 and #7 together ---------------------------------------------------------------

    @Test
    fun `teardown during an in-flight request cancels it and the next instance respects the cooldown`() = runTest {
        val h = Harness(this)
        val gate = CommandGate(20_000) { testScheduler.currentTime }
        val runner = CommandRunner(gate)
        val events = mutableListOf<Event>()
        var calls = 0
        var requestCancelled = false
        val slowHa: suspend () -> Result<Unit> = {
            calls++
            try {
                delay(25_000)
                Result.success(Unit)
            } catch (e: CancellationException) {
                requestCancelled = true // KarooHttp's finally removes its consumer here
                throw e
            }
        }
        fun press() = runner.run(h.get().scope, { true }, slowHa) { events += it }

        press()
        advanceTimeBy(10_000)
        h.lifecycle.reset() // GarageExtension.onDestroy mid-request
        runCurrent()
        assertTrue(requestCancelled)
        assertEquals(0, h.connections[0])
        assertEquals(listOf<Event>(Event.Sending), events)

        // GarageExtension.onCreate again; the rider presses straight away.
        press()
        assertEquals(Event.CoolingDown, events.last())
        assertEquals(1, calls)

        advanceTimeBy(20_000)
        press()
        assertEquals(Event.Sending, events.last())
        advanceUntilIdle()
        assertEquals(Event.Done, events.last())
        assertEquals(2, calls)
        assertEquals(1, h.connections[0])
        assertEquals(2, h.creates)
    }
}
