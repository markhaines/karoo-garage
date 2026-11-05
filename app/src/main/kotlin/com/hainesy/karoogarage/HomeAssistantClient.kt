package com.hainesy.karoogarage

import java.io.IOException

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
            ?: return Result.failure(IOException("not configured"))
        // A URL stored by an older version may fail today's rules (remote http, a path).
        // Say so, rather than "not configured", and never send the token to it.
        ServerUrl.parse(state.baseUrl).onFailure { return Result.failure(IOException(it.message)) }
        if (!state.isValid()) {
            return Result.failure(IOException("not configured"))
        }
        return when (state.authMode) {
            AuthMode.LEGACY_TOKEN -> callService(state, state.token!!)
            AuthMode.OAUTH -> triggerWithOauth(state)
        }
    }

    /** Refresh and 401-retry rules live in [OauthRetry], where they are unit-tested. */
    private suspend fun triggerWithOauth(state: GarageState): Result<Unit> =
        OauthRetry.callWithRetry(
            token = { freshAccessToken(state) },
            forceRefresh = { refreshAndCache(state) },
            call = { token -> callService(state, token) },
        )

    /**
     * Returns a usable access token, refreshing via the stored refresh token
     * when the cached one is missing or about to expire. Also used by the
     * entity picker.
     */
    suspend fun freshAccessToken(state: GarageState): Result<String> {
        val cached = state.accessToken
        if (cached != null &&
            OauthRetry.cachedTokenUsable(cached, state.accessTokenExpiresAt, System.currentTimeMillis())
        ) {
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
            .recoverCatching { error -> throw OauthRetry.refreshFailure(error) }

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
        /**
         * BLE-tunnelled round trips have been observed taking 20s+ in the
         * wild; the default 15s timeout declared failure on commands that
         * were still in transit (and then arrived).
         */
        const val RIDE_TIMEOUT_MS = 30_000L
    }
}
