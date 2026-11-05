package com.hainesy.karoogarage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The OAuth half of a garage press: cached token reuse, refresh before expiry, one forced
 * refresh and one retry on a 401, and "log in again" only when the refresh token is dead.
 * A bug here either opens nothing (rider stuck at the door) or loops refreshes over a slow
 * Bluetooth tunnel. Issue #8. The transport is a fake: nothing here talks to Home Assistant.
 */
class OauthRetryTest {

    /** Records every bearer token the "service" saw and answers from a script. */
    private class FakeService(answers: List<Result<Unit>> = emptyList()) {
        private val script = ArrayDeque(answers)
        val tokensSeen = mutableListOf<String>()
        suspend fun call(token: String): Result<Unit> {
            tokensSeen += token
            return script.removeFirstOrNull() ?: Result.success(Unit)
        }
    }

    private class FakeRefresh(answers: List<Result<String>> = emptyList()) {
        private val script = ArrayDeque(answers)
        var calls = 0
        suspend fun refresh(): Result<String> {
            calls++
            return script.removeFirst().recoverCatching { throw OauthRetry.refreshFailure(it) }
        }
    }

    private fun status(code: Int): Result<Unit> = Result.failure(HttpStatusException(code))

    private fun run(
        cached: Result<String>,
        refresh: FakeRefresh,
        service: FakeService,
    ): Result<Unit> = runBlocking {
        OauthRetry.callWithRetry(
            token = { cached },
            forceRefresh = { refresh.refresh() },
            call = { service.call(it) },
        )
    }

    // --- cache expiry -------------------------------------------------------------------

    @Test
    fun `a token with more than a minute left is reused`() {
        assertTrue(OauthRetry.cachedTokenUsable("t", expiresAtMillis = 1_000_000, nowMillis = 1_000_000 - 61_000))
    }

    @Test
    fun `a token inside the one-minute margin is refreshed`() {
        assertFalse(OauthRetry.cachedTokenUsable("t", expiresAtMillis = 1_000_000, nowMillis = 1_000_000 - 60_000))
        assertFalse(OauthRetry.cachedTokenUsable("t", expiresAtMillis = 1_000_000, nowMillis = 1_000_000 - 1))
    }

    @Test
    fun `an expired or missing token is refreshed`() {
        assertFalse(OauthRetry.cachedTokenUsable("t", expiresAtMillis = 1_000_000, nowMillis = 2_000_000))
        assertFalse(OauthRetry.cachedTokenUsable(null, expiresAtMillis = Long.MAX_VALUE, nowMillis = 0))
    }

    // --- the call ------------------------------------------------------------------------

    @Test
    fun `success first time makes one call and no refresh`() {
        val refresh = FakeRefresh()
        val service = FakeService(listOf(Result.success(Unit)))
        assertTrue(run(Result.success("cached"), refresh, service).isSuccess)
        assertEquals(listOf("cached"), service.tokensSeen)
        assertEquals(0, refresh.calls)
    }

    @Test
    fun `a 401 refreshes once and retries with the new token`() {
        val refresh = FakeRefresh(listOf(Result.success("fresh")))
        val service = FakeService(listOf(status(401), Result.success(Unit)))
        assertTrue(run(Result.success("revoked"), refresh, service).isSuccess)
        assertEquals(listOf("revoked", "fresh"), service.tokensSeen)
        assertEquals(1, refresh.calls)
    }

    @Test
    fun `a second 401 is returned, not retried again`() {
        val refresh = FakeRefresh(listOf(Result.success("fresh")))
        val service = FakeService(listOf(status(401), status(401)))
        val result = run(Result.success("revoked"), refresh, service)
        assertEquals(401, (result.exceptionOrNull() as HttpStatusException).statusCode)
        assertEquals(2, service.tokensSeen.size)
        assertEquals(1, refresh.calls)
    }

    @Test
    fun `other failures are not retried`() {
        for (code in listOf(400, 403, 404, 500, 502)) {
            val refresh = FakeRefresh()
            val service = FakeService(listOf(status(code)))
            val result = run(Result.success("cached"), refresh, service)
            assertEquals(code, (result.exceptionOrNull() as HttpStatusException).statusCode)
            assertEquals("$code", 1, service.tokensSeen.size)
            assertEquals("$code", 0, refresh.calls)
        }
    }

    @Test
    fun `a network error or timeout is not retried`() {
        // Over the Bluetooth tunnel a timeout may still land: a retry could double-toggle.
        val refresh = FakeRefresh()
        val timeout = IOException("timed out after 30000ms")
        val service = FakeService(listOf(Result.failure(timeout)))
        assertSame(timeout, run(Result.success("cached"), refresh, service).exceptionOrNull())
        assertEquals(1, service.tokensSeen.size)
        assertEquals(0, refresh.calls)
    }

    @Test
    fun `no usable token means no call at all`() {
        val service = FakeService()
        val dead = ReauthRequiredException("login expired")
        assertSame(dead, run(Result.failure(dead), FakeRefresh(), service).exceptionOrNull())
        assertTrue(service.tokensSeen.isEmpty())
    }

    @Test
    fun `a 401 followed by a dead refresh token asks for a new login and does not retry`() {
        val refresh = FakeRefresh(listOf(Result.failure(HttpStatusException(400, "invalid_grant"))))
        val service = FakeService(listOf(status(401)))
        val result = run(Result.success("revoked"), refresh, service)
        assertTrue(result.exceptionOrNull() is ReauthRequiredException)
        assertEquals(1, service.tokensSeen.size)
    }

    @Test
    fun `a 401 followed by an unreachable token endpoint reports the network error`() {
        val offline = IOException("no route to host")
        val refresh = FakeRefresh(listOf(Result.failure(offline)))
        val service = FakeService(listOf(status(401)))
        assertSame(offline, run(Result.success("revoked"), refresh, service).exceptionOrNull())
        assertEquals(1, service.tokensSeen.size)
    }

    // --- refresh failures -----------------------------------------------------------------

    @Test
    fun `refresh answered 400 or 401 means log in again`() {
        assertTrue(OauthRetry.refreshFailure(HttpStatusException(400, "invalid_grant")) is ReauthRequiredException)
        assertTrue(OauthRetry.refreshFailure(HttpStatusException(401)) is ReauthRequiredException)
    }

    @Test
    fun `other refresh failures pass through unchanged`() {
        for (error in listOf(HttpStatusException(500), HttpStatusException(403), IOException("timed out"))) {
            assertSame(error, OauthRetry.refreshFailure(error))
        }
    }
}
