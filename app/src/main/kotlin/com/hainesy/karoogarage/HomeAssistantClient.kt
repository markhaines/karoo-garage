package com.hainesy.karoogarage

import java.io.IOException

/**
 * Thrown when OAuth credentials are dead (revoked, expired after 90 idle days,
 * or wiped by an HA reinstall) and the user has to log in again in the app.
 */
class ReauthRequiredException(message: String) : IOException(message)

/**
 * Calls the configured Home Assistant service, handling both auth modes:
 * a legacy long-lived token used as-is, or an OAuth access token that is
 * silently refreshed when stale (HA access tokens live 30 minutes).
 */
class HomeAssistantClient(
    private val http: KarooHttp,
    private val authClient: AuthClient,
    private val configStore: ConfigStore,
) {

    suspend fun trigger(): Result<Unit> {
        val state = configStore.load()
        if (state == null || !state.isValid()) {
            return Result.failure(IOException("not configured"))
        }
        return when (state.authMode) {
            AuthMode.LEGACY_TOKEN -> callService(state, state.token!!)
            AuthMode.OAUTH -> triggerWithOauth(state)
        }
    }

    private suspend fun triggerWithOauth(state: GarageState): Result<Unit> {
        val accessToken = freshAccessToken(state)
            .getOrElse { return Result.failure(it) }

        val first = callService(state, accessToken)
        val status = (first.exceptionOrNull() as? HttpStatusException)?.statusCode
        if (status != 401) {
            return first
        }

        // The cached token was revoked server-side — force one refresh and retry.
        val fresh = refreshAndCache(state)
            .getOrElse { return Result.failure(it) }
        return callService(state, fresh)
    }

    /**
     * Returns a usable access token, refreshing via the stored refresh token
     * when the cached one is missing or about to expire. Also used by the
     * entity picker.
     */
    suspend fun freshAccessToken(state: GarageState): Result<String> {
        val cached = state.accessToken
        val stillValid = cached != null &&
            state.accessTokenExpiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()
        if (cached != null && stillValid) {
            return Result.success(cached)
        }
        return refreshAndCache(state)
    }

    private suspend fun refreshAndCache(state: GarageState): Result<String> =
        authClient.refreshAccessToken(state.baseUrl, state.refreshToken!!)
            .map { tokens ->
                configStore.saveAccessToken(
                    accessToken = tokens.accessToken,
                    expiresAtMillis = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L,
                )
                tokens.accessToken
            }
            .recoverCatching { error ->
                // HA answers a dead refresh token (revoked, 90 days idle, or
                // wiped by a reinstall) with 400 invalid_grant.
                if ((error as? HttpStatusException)?.statusCode in listOf(400, 401)) {
                    throw ReauthRequiredException("login expired")
                }
                throw error
            }

    private suspend fun callService(state: GarageState, bearerToken: String): Result<Unit> =
        http.postJson(
            url = "${state.baseUrl}/api/services/${state.domain}/${state.service}",
            json = """{"entity_id":"${state.entityId.jsonEscape()}"}""",
            headers = mapOf("Authorization" to "Bearer $bearerToken"),
            timeoutMs = RIDE_TIMEOUT_MS,
        ).mapCatching { response ->
            if (!response.isSuccess) {
                throw HttpStatusException(response.statusCode)
            }
        }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        /** Refresh when the cached access token has under a minute left. */
        private const val EXPIRY_MARGIN_MS = 60_000L

        /**
         * BLE-tunnelled round trips have been observed taking 20s+ in the
         * wild; the default 15s timeout declared failure on commands that
         * were still in transit (and then arrived).
         */
        const val RIDE_TIMEOUT_MS = 30_000L
    }
}
