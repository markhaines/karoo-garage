package com.hainesy.karoogarage

/**
 * The OAuth rules for a ride-time call, kept free of Android and karoo-ext so they can be
 * unit-tested against a fake transport:
 *
 * - reuse the cached access token while it has more than [EXPIRY_MARGIN_MS] left (HA access
 *   tokens live 30 minutes), otherwise refresh first;
 * - on a 401 from the call itself (token revoked server-side), refresh exactly once and
 *   retry once. A second 401 is returned, never looped on;
 * - a refresh answered 400 or 401 means the refresh token is dead: [ReauthRequiredException].
 */
internal object OauthRetry {

    /** Refresh when the cached access token has under a minute left. */
    const val EXPIRY_MARGIN_MS = 60_000L

    fun cachedTokenUsable(token: String?, expiresAtMillis: Long, nowMillis: Long): Boolean =
        token != null && expiresAtMillis - EXPIRY_MARGIN_MS > nowMillis

    /**
     * HA answers a dead refresh token (revoked, 90 days idle, or wiped by a reinstall) with
     * 400 invalid_grant. Anything else (network, timeout, 5xx) is passed through unchanged:
     * it is not a reason to make the rider log in again.
     */
    fun refreshFailure(error: Throwable): Throwable =
        if ((error as? HttpStatusException)?.statusCode in listOf(400, 401)) {
            ReauthRequiredException("login expired")
        } else {
            error
        }

    /**
     * @param token a usable access token, refreshing first if the cached one is stale
     * @param forceRefresh a new access token, ignoring the cache
     * @param call the service call with a bearer token
     */
    suspend fun <T> callWithRetry(
        token: suspend () -> Result<String>,
        forceRefresh: suspend () -> Result<String>,
        call: suspend (String) -> Result<T>,
    ): Result<T> {
        val first = call(token().getOrElse { return Result.failure(it) })
        if ((first.exceptionOrNull() as? HttpStatusException)?.statusCode != 401) {
            return first
        }
        val fresh = forceRefresh().getOrElse { return Result.failure(it) }
        return call(fresh)
    }
}
