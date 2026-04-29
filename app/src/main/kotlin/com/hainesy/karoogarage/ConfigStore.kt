package com.hainesy.karoogarage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun load(): Config? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val entityId = prefs.getString(KEY_ENTITY_ID, null) ?: return null
        val domain = prefs.getString(KEY_DOMAIN, Config.DEFAULT_DOMAIN) ?: Config.DEFAULT_DOMAIN
        val service = prefs.getString(KEY_SERVICE, Config.DEFAULT_SERVICE) ?: Config.DEFAULT_SERVICE
        return Config(baseUrl, token, entityId, domain, service)
    }

    fun save(config: Config) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_ENTITY_ID, config.entityId)
            .putString(KEY_DOMAIN, config.domain)
            .putString(KEY_SERVICE, config.service)
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
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SERVICE = "service"
    }
}
