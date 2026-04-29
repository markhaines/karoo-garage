package com.hainesy.karoogarage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HomeAssistantClient(
    private val config: Config,
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun trigger(): Result<Unit> = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl}/api/services/${config.domain}/${config.service}"
        val body = """{"entity_id":"${config.entityId.jsonEscape()}"}"""
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${response.message}")
                }
            }
        }
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
