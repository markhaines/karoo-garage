package com.hainesy.karoogarage

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * A non-2xx HTTP response, carrying the status for callers that branch on it
 * (401 → retry with fresh token, 400 on refresh → re-login required).
 */
class HttpStatusException(
    val statusCode: Int,
    detail: String? = null,
) : java.io.IOException(if (detail != null) "HTTP $statusCode: $detail" else "HTTP $statusCode")

/**
 * HTTP response from the karoo-ext bridge.
 */
data class KarooHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    fun bodyText(): String = body?.decodeToString().orEmpty()
}

/**
 * Single entry point for HTTP through Karoo's network stack (WiFi direct, or
 * BLE-tunnelled via the Hammerhead Companion app when WiFi is down).
 *
 * karoo-ext provides no timeout of its own — a request over a dead tunnel sits
 * in Queued/InProgress forever — so every call here is wrapped in a caller
 * timeout, and the consumer is always removed when we're done with it.
 *
 * Request and response bodies are capped at 100KB by the bridge.
 */
class KarooHttp(private val karooSystem: KarooSystemService) {

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        waitForConnection: Boolean = true,
    ): Result<KarooHttpResponse> {
        val params = OnHttpResponse.MakeHttpRequest(
            method = method,
            url = url,
            headers = headers,
            body = body,
            waitForConnection = waitForConnection,
        )

        val deferred = CompletableDeferred<Result<KarooHttpResponse>>()
        val consumerId = karooSystem.addConsumer<OnHttpResponse>(
            params = params,
            onError = { message ->
                deferred.complete(Result.failure(IOException(message)))
            },
        ) { event ->
            when (val state = event.state) {
                is HttpResponseState.Complete -> {
                    val result = if (state.error != null) {
                        Result.failure(IOException(state.error))
                    } else {
                        Result.success(
                            KarooHttpResponse(state.statusCode, state.headers, state.body),
                        )
                    }
                    deferred.complete(result)
                }
                else -> Unit
            }
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(IOException("timed out after ${timeoutMs}ms"))
        } finally {
            karooSystem.removeConsumer(consumerId)
        }
    }

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        waitForConnection: Boolean = true,
    ): Result<KarooHttpResponse> = request(
        method = "POST",
        url = url,
        headers = headers + mapOf("Content-Type" to "application/json"),
        body = json.toByteArray(),
        timeoutMs = timeoutMs,
        waitForConnection = waitForConnection,
    )

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        waitForConnection: Boolean = true,
    ): Result<KarooHttpResponse> = request(
        method = "POST",
        url = url,
        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        body = fields.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }.toByteArray(),
        timeoutMs = timeoutMs,
        waitForConnection = waitForConnection,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }
}
