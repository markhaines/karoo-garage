package com.hainesy.karoogarage

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Calls a Home Assistant service. Routes through Karoo's network stack
 * via the karoo-ext OnHttpResponse event, so requests work whether the
 * Karoo is on WiFi or BLE-tethered to the Hammerhead Companion app.
 */
class HomeAssistantClient(private val karooSystem: KarooSystemService) {

    suspend fun trigger(config: Config, timeoutMs: Long = 15_000): Result<Unit> {
        val params = OnHttpResponse.MakeHttpRequest(
            method = "POST",
            url = "${config.baseUrl}/api/services/${config.domain}/${config.service}",
            headers = mapOf(
                "Authorization" to "Bearer ${config.token}",
                "Content-Type" to "application/json",
            ),
            body = """{"entity_id":"${config.entityId.jsonEscape()}"}""".toByteArray(),
        )

        val deferred = CompletableDeferred<Result<Unit>>()
        val consumerId = karooSystem.addConsumer<OnHttpResponse>(
            params = params,
            onError = { message ->
                deferred.complete(Result.failure(IOException(message)))
            },
        ) { event ->
            when (val state = event.state) {
                is HttpResponseState.Complete -> {
                    val result = when {
                        state.error != null -> Result.failure(IOException(state.error))
                        state.statusCode in 200..299 -> Result.success(Unit)
                        else -> Result.failure(IOException("HTTP ${state.statusCode}"))
                    }
                    deferred.complete(result)
                }
                else -> Unit
            }
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            karooSystem.removeConsumer(consumerId)
            Result.failure(IOException("timed out after ${timeoutMs}ms"))
        }
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
