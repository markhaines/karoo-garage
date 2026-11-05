package com.hainesy.karoogarage

enum class AuthMode { LEGACY_TOKEN, OAUTH }

/**
 * Everything the extension needs at ride time, whichever auth mode is active.
 */
data class GarageState(
    val baseUrl: String,
    val entityId: String,
    /** Friendly name of the entity, for display only. */
    val entityName: String?,
    val domain: String,
    val service: String,
    val authMode: AuthMode,
    /** Legacy long-lived access token (LEGACY_TOKEN mode). */
    val token: String?,
    /** OAuth refresh token (OAUTH mode). */
    val refreshToken: String?,
    /** Cached short-lived access token (OAUTH mode). */
    val accessToken: String?,
    /** Epoch millis when [accessToken] expires. */
    val accessTokenExpiresAt: Long,
    /** Display name of the logged-in HA user (OAUTH mode, informational). */
    val accountName: String?,
) {
    fun isValid(): Boolean {
        val serverOk = baseUrl.isNotBlank() &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
        val actionOk = entityId.isNotBlank() && domain.isNotBlank() && service.isNotBlank()
        val authOk = when (authMode) {
            AuthMode.LEGACY_TOKEN -> !token.isNullOrBlank()
            AuthMode.OAUTH -> !refreshToken.isNullOrBlank()
        }
        return serverOk && actionOk && authOk
    }
}
