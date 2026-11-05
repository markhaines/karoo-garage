package com.hainesy.karoogarage

import com.hainesy.karoogarage.CommandRunner.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One garage command at a time (#5). Home Assistant is a fake suspend function on virtual
 * time; nothing here touches a network, a Karoo or a door. Over the Bluetooth tunnel a
 * request can take 20s+, and a second `toggle` landing after the first reverses the door, so
 * these pin that a press can never start a second request while one is unresolved, and that
 * the cooldown runs from the request's end, not the press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GarageCommandTest {

    private val cooldown = 20_000L

    /** A fake HA call that takes [latencyMs] and then returns [result]. */
    private class FakeHa(var latencyMs: Long, var result: () -> Result<Unit> = { Result.success(Unit) }) {
        var calls = 0
        suspend fun send(): Result<Unit> {
            calls++
            delay(latencyMs)
            return result()
        }
    }

    private class Harness(test: TestScope, cooldownMs: Long) {
        val gate = CommandGate(cooldownMs) { test.testScheduler.currentTime }
        val runner = CommandRunner(gate)
        val events = mutableListOf<Event>()
        var scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        var configured = true

        fun press(ha: FakeHa) = runner.run(scope, { configured }, ha::send) { events += it }
    }

    @Test
    fun `a request lasting over 20 seconds emits exactly one call`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 25_000)

        h.press(ha)
        runCurrent()
        // Re-presses at 5s, 15s, then 21s and 24s: past the old 20s timestamp debounce,
        // which let a second toggle through here, but still before the reply at 25s.
        for (at in listOf(5_000L, 15_000L, 21_000L, 24_000L)) {
            advanceTimeBy(at - testScheduler.currentTime)
            h.press(ha)
            runCurrent()
        }

        assertEquals(1, ha.calls)
        assertEquals(listOf<Event>(Event.Sending) + List(4) { Event.StillSending }, h.events)

        advanceUntilIdle()
        assertEquals(1, ha.calls)
        assertEquals(Event.Done, h.events.last())
    }

    @Test
    fun `cooldown starts when the request resolves, not when it was pressed`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 25_000)
        h.press(ha)
        advanceTimeBy(25_001) // resolved at t=25s
        assertEquals(Event.Done, h.events.last())

        advanceTimeBy(19_000) // t=44s: 19s after it resolved, 44s after the press
        h.press(ha)
        assertEquals(Event.CoolingDown, h.events.last())

        advanceTimeBy(1_000) // t=45s: cooldown over
        h.press(ha)
        assertEquals(Event.Sending, h.events.last())
        advanceUntilIdle()
        assertEquals(2, ha.calls)
    }

    @Test
    fun `an unconfigured press neither calls Home Assistant nor consumes the cooldown`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 1_000)
        h.configured = false
        h.press(ha)
        assertEquals(listOf<Event>(Event.NotConfigured), h.events)

        h.configured = true
        h.press(ha) // straight after: not blocked by the unconfigured attempt
        assertEquals(Event.Sending, h.events.last())
        advanceUntilIdle()
        assertEquals(1, ha.calls)
    }

    @Test
    fun `sending, timeout, completion, failure and debounce are distinct events`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 30_000, result = { Result.failure(RequestTimeoutException("timed out")) })
        h.press(ha)
        runCurrent()
        h.press(ha)
        advanceTimeBy(30_001)
        h.press(ha)
        assertEquals(listOf(Event.Sending, Event.StillSending, Event.TimedOut, Event.CoolingDown), h.events)

        advanceTimeBy(cooldown)
        val boom = IOException("HTTP 500")
        ha.result = { Result.failure(boom) }
        h.press(ha)
        advanceUntilIdle()
        assertEquals(listOf(Event.Sending, Event.Failed(boom)), h.events.takeLast(2))

        advanceTimeBy(cooldown)
        ha.result = { Result.success(Unit) }
        h.press(ha)
        advanceUntilIdle()
        assertEquals(Event.Done, h.events.last())
    }

    @Test
    fun `a send that throws is reported as a failure and releases the gate`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 10, result = { throw IllegalStateException("bug") })
        h.press(ha)
        advanceUntilIdle()
        assertTrue(h.events.last() is Event.Failed)
        advanceTimeBy(cooldown)
        h.press(ha)
        assertEquals(Event.Sending, h.events.last())
    }

    @Test
    fun `teardown mid-request still starts the cooldown, so a recreated runtime cannot double-toggle`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 25_000)
        h.press(ha)
        advanceTimeBy(10_000)

        h.scope.cancel() // extension destroyed with the request in flight
        runCurrent()
        assertEquals(listOf<Event>(Event.Sending), h.events) // no terminal event to dead listeners

        // A new extension instance, a fresh scope, the same gate.
        h.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        h.press(ha)
        assertEquals(Event.CoolingDown, h.events.last())

        advanceTimeBy(cooldown)
        h.press(ha)
        assertEquals(Event.Sending, h.events.last())
        advanceUntilIdle()
        assertEquals(2, ha.calls)
    }

    @Test
    fun `a press into an already-cancelled scope does not wedge the gate`() = runTest {
        val h = Harness(this, cooldown)
        val ha = FakeHa(latencyMs = 1_000)
        h.scope.cancel()
        h.press(ha)
        runCurrent()
        assertEquals(0, ha.calls)
        assertTrue(!h.gate.isInFlight)
    }

    @Test
    fun `a clock that steps backwards ends the cooldown instead of extending it`() {
        var now = 1_000_000_000L
        val gate = CommandGate(cooldown) { now }
        assertEquals(CommandGate.Decision.START, gate.tryStart())
        gate.finish()
        now += 5_000
        assertEquals(CommandGate.Decision.COOLING_DOWN, gate.tryStart())

        now -= 3_600_000 // time sync steps the clock back an hour
        assertEquals(CommandGate.Decision.START, gate.tryStart())
    }

    @Test
    fun `simultaneous presses from many threads start one command`() {
        val gate = CommandGate(cooldown)
        val starts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val done = CountDownLatch(64)
        repeat(64) {
            pool.execute {
                go.await()
                if (gate.tryStart() == CommandGate.Decision.START) starts.incrementAndGet()
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, starts.get())
    }
}
