package com.hainesy.karoogarage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

class ConfigStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): GarageState? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        return GarageState(
            baseUrl = baseUrl,
            entityId = prefs.getString(KEY_ENTITY_ID, "").orEmpty(),
            entityName = prefs.getString(KEY_ENTITY_NAME, null),
            domain = prefs.getString(KEY_DOMAIN, Config.DEFAULT_DOMAIN) ?: Config.DEFAULT_DOMAIN,
            service = prefs.getString(KEY_SERVICE, Config.DEFAULT_SERVICE) ?: Config.DEFAULT_SERVICE,
            authMode = if (prefs.getString(KEY_AUTH_MODE, null) == AUTH_MODE_OAUTH) {
                AuthMode.OAUTH
            } else {
                AuthMode.LEGACY_TOKEN
            },
            token = prefs.getString(KEY_TOKEN, null),
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null),
            accessTokenExpiresAt = prefs.getLong(KEY_ACCESS_TOKEN_EXPIRES_AT, 0L),
            accountName = prefs.getString(KEY_ACCOUNT_NAME, null),
        )
    }

    /**
     * Legacy path: saves a token-mode config (manual entry or .kgcfg import).
     * Drops any stored OAuth credentials — mirror image of [saveOauthLogin].
     */
    fun save(config: Config) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_ENTITY_ID, config.entityId)
            .putString(KEY_DOMAIN, config.domain)
            .putString(KEY_SERVICE, config.service)
            .putString(KEY_AUTH_MODE, AUTH_MODE_LEGACY)
            .remove(KEY_ENTITY_NAME)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN_EXPIRES_AT)
            .remove(KEY_ACCOUNT_NAME)
            .apply()
    }

    /** Stores a successful OAuth login. Clears any legacy token. */
    fun saveOauthLogin(
        baseUrl: String,
        refreshToken: String,
        accessToken: String,
        expiresAtMillis: Long,
        accountName: String?,
    ) {
        // A different server means the stored entity belongs to another HA —
        // drop the action so the picker runs again (HA answers 200 even for
        // unknown entities, so a stale one would fail silently mid-ride).
        val previousBaseUrl = prefs.getString(KEY_BASE_URL, null)
        if (previousBaseUrl != null && previousBaseUrl != baseUrl) {
            prefs.edit()
                .remove(KEY_ENTITY_ID)
                .remove(KEY_ENTITY_NAME)
                .remove(KEY_DOMAIN)
                .remove(KEY_SERVICE)
                .apply()
        }
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_AUTH_MODE, AUTH_MODE_OAUTH)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT, expiresAtMillis)
            .putString(KEY_ACCOUNT_NAME, accountName)
            .remove(KEY_TOKEN)
            .apply()
    }

    /**
     * Points the session at a different URL for the SAME instance (e.g. the
     * advertised public address after a LAN login). Refresh tokens aren't
     * host-bound, so the login carries over.
     */
    fun saveBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
    }

    /** Updates the cached short-lived access token after a refresh. */
    fun saveAccessToken(accessToken: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun saveAction(entityId: String, entityName: String?, domain: String, service: String) {
        prefs.edit()
            .putString(KEY_ENTITY_ID, entityId)
            .putString(KEY_ENTITY_NAME, entityName)
            .putString(KEY_DOMAIN, domain)
            .putString(KEY_SERVICE, service)
            .apply()
    }

    /** Drops OAuth credentials but keeps server/action settings. */
    fun clearOauthLogin() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN_EXPIRES_AT)
            .remove(KEY_ACCOUNT_NAME)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILENAME = "karoo_garage_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENTITY_ID = "entity_id"
        private const val KEY_ENTITY_NAME = "entity_name"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SERVICE = "service"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_TOKEN_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val AUTH_MODE_LEGACY = "token"
        private const val AUTH_MODE_OAUTH = "oauth"
    }
}
