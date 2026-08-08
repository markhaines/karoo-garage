package com.hainesy.karoogarage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Implements Home Assistant's native auth against a plain HTTP transport:
 * the login_flow API the HA frontend itself uses (so no WebView is needed),
 * plus the /auth/token code-exchange and refresh endpoints.
 *
 * Flow: POST /auth/login_flow → flow_id → POST /auth/login_flow/{id} with
 * username/password (and MFA code if challenged) → authorization code →
 * POST /auth/token → { access_token (30 min), refresh_token (until revoked) }.
 *
 * client_id is a URL per HA's IndieAuth model. We use the project URL with a
 * redirect_uri on the same host, which HA accepts without fetching the page.
 * The same client_id must be presented on every refresh.
 */
class AuthClient(private val http: KarooHttp) {

    sealed class LoginStep {
        /** HA wants an MFA code (TOTP). */
        data class MfaRequired(val flowId: String) : LoginStep()

        /** Login complete; [code] is the one-time authorization code. */
        data class Success(val code: String) : LoginStep()

        /** HA rejected the attempt but the flow may be retried. */
        data class Failed(val flowId: String?, val message: String) : LoginStep()
    }

    data class TokenSet(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Starts a login flow. Returns the flow_id to submit credentials against. */
    suspend fun startLoginFlow(baseUrl: String): Result<String> {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("redirect_uri", REDIRECT_URI)
            putHandler()
        }
        return http.postJson(
            url = "$baseUrl/auth/login_flow",
            json = body.toString(),
            waitForConnection = false,
        ).mapCatching { response ->
            if (!response.isSuccess) {
                throw HttpStatusException(response.statusCode, describeError(response))
            }
            val obj = json.parseToJsonElement(response.bodyText()).jsonObject
            obj["flow_id"]?.jsonPrimitive?.content
                ?: throw IOException("no flow_id in login_flow response")
        }
    }

    /** Submits username + password for the given flow. */
    suspend fun submitCredentials(
        baseUrl: String,
        flowId: String,
        username: String,
        password: String,
    ): Result<LoginStep> = submitStep(baseUrl, flowId) {
        put("username", username)
        put("password", password)
    }

    /** Submits an MFA (TOTP) code for the given flow. */
    suspend fun submitMfaCode(
        baseUrl: String,
        flowId: String,
        code: String,
    ): Result<LoginStep> = submitStep(baseUrl, flowId) {
        put("code", code)
    }

    private suspend fun submitStep(
        baseUrl: String,
        flowId: String,
        depth: Int = 0,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Result<LoginStep> {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            fields()
        }
        val obj = http.postJson(
            url = "$baseUrl/auth/login_flow/$flowId",
            json = body.toString(),
            waitForConnection = false,
        ).mapCatching { response ->
            if (!response.isSuccess) {
                throw HttpStatusException(response.statusCode, describeError(response))
            }
            json.parseToJsonElement(response.bodyText()).jsonObject
        }.getOrElse { return Result.failure(it) }

        // Accounts with several MFA modules get an extra select_mfa_module
        // step; auto-pick TOTP (or the first module) so the user only ever
        // sees the code prompt. depth bounds the follow-up to a single hop.
        val moduleId = mfaModuleToSelect(obj)
        if (moduleId != null && depth < 1) {
            val nextFlowId = obj["flow_id"]?.jsonPrimitive?.content ?: flowId
            return submitStep(baseUrl, nextFlowId, depth + 1) {
                put("multi_factor_auth_module", moduleId)
            }
        }
        return Result.success(parseLoginStep(obj))
    }

    /** Returns the MFA module to auto-select, or null if this isn't that step. */
    private fun mfaModuleToSelect(obj: JsonObject): String? {
        if (obj["type"]?.jsonPrimitive?.content != "form") return null
        if (obj["step_id"]?.jsonPrimitive?.content != "select_mfa_module") return null
        if ((obj["errors"] as? JsonObject)?.isNotEmpty() == true) return null
        val options = (obj["data_schema"] as? kotlinx.serialization.json.JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "multi_factor_auth_module" }
            ?.get("options") as? kotlinx.serialization.json.JsonArray
            ?: return null
        val ids = options.mapNotNull { option ->
            (option as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonPrimitive?.content
        }
        return ids.firstOrNull { it == "totp" } ?: ids.firstOrNull()
    }

    private fun parseLoginStep(obj: JsonObject): LoginStep {
        val type = obj["type"]?.jsonPrimitive?.content
        val flowId = obj["flow_id"]?.jsonPrimitive?.content
        return when (type) {
            "create_entry" -> {
                val code = obj["result"]?.jsonPrimitive?.content
                if (code != null) LoginStep.Success(code)
                else LoginStep.Failed(flowId, "login succeeded but no code returned")
            }
            "form" -> {
                val stepId = obj["step_id"]?.jsonPrimitive?.content
                val errors = obj["errors"] as? JsonObject
                val baseError = errors?.get("base")?.jsonPrimitive?.content
                when {
                    baseError != null -> LoginStep.Failed(flowId, humaniseError(baseError))
                    stepId == "mfa" && flowId != null -> LoginStep.MfaRequired(flowId)
                    else -> LoginStep.Failed(flowId, "unexpected login step: $stepId")
                }
            }
            "abort" -> LoginStep.Failed(
                null,
                obj["reason"]?.jsonPrimitive?.content ?: "login aborted",
            )
            else -> LoginStep.Failed(flowId, "unexpected response type: $type")
        }
    }

    /** Exchanges the one-time authorization code for tokens. */
    suspend fun exchangeCode(baseUrl: String, code: String): Result<TokenSet> =
        http.postForm(
            url = "$baseUrl/auth/token",
            fields = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to CLIENT_ID,
            ),
            waitForConnection = false,
        ).mapCatching(::parseTokenResponse)

    /** Gets a fresh access token. Works over the BLE tunnel mid-ride. */
    suspend fun refreshAccessToken(baseUrl: String, refreshToken: String): Result<TokenSet> =
        http.postForm(
            url = "$baseUrl/auth/token",
            fields = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
            ),
        ).mapCatching(::parseTokenResponse)

    /** Revokes the refresh token (logout). HA returns 200 regardless. */
    suspend fun revoke(baseUrl: String, refreshToken: String): Result<Unit> =
        http.postForm(
            url = "$baseUrl/auth/revoke",
            fields = mapOf("token" to refreshToken),
            waitForConnection = false,
        ).map { }

    private fun parseTokenResponse(response: KarooHttpResponse): TokenSet {
        if (!response.isSuccess) {
            throw HttpStatusException(response.statusCode, describeError(response))
        }
        val obj = json.parseToJsonElement(response.bodyText()).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content
            ?: throw IOException("no access_token in token response")
        return TokenSet(
            accessToken = accessToken,
            refreshToken = obj["refresh_token"]?.jsonPrimitive?.content,
            expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1800L,
        )
    }

    private fun describeError(response: KarooHttpResponse): String? =
        // /auth/token errors arrive as {"error": "...", "error_description": "..."}
        runCatching {
            val obj = json.parseToJsonElement(response.bodyText()).jsonObject
            obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
        }.getOrNull()

    private fun humaniseError(code: String): String = when (code) {
        "invalid_auth" -> "wrong username or password"
        "invalid_code" -> "wrong verification code"
        "invalid_auth_module" -> "verification method unavailable"
        else -> code
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHandler() {
        put(
            "handler",
            kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("homeassistant"))
                add(kotlinx.serialization.json.JsonNull)
            },
        )
    }

    companion object {
        // HA's IndieAuth: client_id is a URL; redirect_uri on the same host is
        // accepted without HA fetching the page, so this works for airgapped
        // installs too. Must stay stable — refresh calls present the same id.
        const val CLIENT_ID = "https://github.com/markhaines/karoo-garage"
        const val REDIRECT_URI = "https://github.com/markhaines/karoo-garage"
    }
}
