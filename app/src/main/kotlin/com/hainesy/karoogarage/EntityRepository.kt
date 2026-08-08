package com.hainesy.karoogarage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

data class PickableEntity(
    val entityId: String,
    val friendlyName: String,
) {
    val domain: String get() = entityId.substringBefore('.')
}

/**
 * Fetches the list of user-pickable entities from Home Assistant.
 *
 * Uses POST /api/template rather than GET /api/states: a full states dump on a
 * big install easily exceeds the karoo-ext bridge's 100KB response cap, while
 * a rendered [[entity_id, name], ...] list for the actionable domains stays
 * a few KB.
 */
class EntityRepository(private val http: KarooHttp) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchEntities(
        baseUrl: String,
        accessToken: String,
    ): Result<List<PickableEntity>> {
        // One request per domain, sliced: a large install's full list blows
        // the bridge's 100KB response cap (seen in the wild as
        // RESPONSE_TOO_LARGE), but CHUNK entities of one domain never do.
        val all = mutableListOf<PickableEntity>()
        for (domain in PICKABLE_DOMAINS) {
            var offset = 0
            while (true) {
                val slice = fetchSlice(baseUrl, accessToken, domain, offset)
                    .getOrElse { return Result.failure(it) }
                all += slice
                if (slice.size < CHUNK) break
                offset += CHUNK
            }
        }
        return Result.success(
            all.sortedWith(
                compareBy(
                    { DOMAIN_ORDER.indexOf(it.domain).let { i -> if (i < 0) DOMAIN_ORDER.size else i } },
                    { it.friendlyName.lowercase() },
                ),
            ),
        )
    }

    private suspend fun fetchSlice(
        baseUrl: String,
        accessToken: String,
        domain: String,
        offset: Int,
    ): Result<List<PickableEntity>> {
        val body = buildJsonObject {
            put("template", sliceTemplate(domain, offset))
        }
        return http.postJson(
            url = "$baseUrl/api/template",
            json = body.toString(),
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            timeoutMs = 30_000L,
            waitForConnection = false,
        ).mapCatching { response ->
            if (!response.isSuccess) {
                throw IOException("HTTP ${response.statusCode}")
            }
            json.parseToJsonElement(response.bodyText()).jsonArray.map { element ->
                val pair = element.jsonArray
                PickableEntity(
                    entityId = pair[0].jsonPrimitive.content,
                    friendlyName = pair[1].jsonPrimitive.content,
                )
            }
        }
    }

    /**
     * Returns the instance's advertised public URL (HA Settings → System →
     * Network → "Internet" URL), or null when none is configured.
     */
    suspend fun fetchExternalUrl(
        baseUrl: String,
        accessToken: String,
    ): Result<String?> = http.request(
        method = "GET",
        url = "$baseUrl/api/config",
        headers = mapOf("Authorization" to "Bearer $accessToken"),
        waitForConnection = false,
    ).mapCatching { response ->
        if (!response.isSuccess) {
            throw HttpStatusException(response.statusCode)
        }
        val external = json.parseToJsonElement(response.bodyText())
            .jsonObject["external_url"]
        when {
            external == null || external is JsonNull -> null
            else -> external.jsonPrimitive.content.trim().trimEnd('/')
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    companion object {
        val PICKABLE_DOMAINS = listOf(
            "cover", "switch", "light", "lock", "fan",
            "script", "scene", "button", "input_button", "automation",
        )

        /** Covers first — it's a garage-door app. */
        private val DOMAIN_ORDER = PICKABLE_DOMAINS

        /** Services offered per domain; first entry is the default. */
        val SERVICES_BY_DOMAIN: Map<String, List<String>> = mapOf(
            "cover" to listOf("toggle", "open_cover", "close_cover", "stop_cover"),
            "switch" to listOf("toggle", "turn_on", "turn_off"),
            "light" to listOf("toggle", "turn_on", "turn_off"),
            "lock" to listOf("unlock", "lock", "open"),
            "fan" to listOf("toggle", "turn_on", "turn_off"),
            "script" to listOf("turn_on"),
            "scene" to listOf("turn_on"),
            "button" to listOf("press"),
            "input_button" to listOf("press"),
            "automation" to listOf("trigger"),
        )

        fun servicesFor(domain: String): List<String> =
            SERVICES_BY_DOMAIN[domain] ?: listOf("toggle")

        /** ~60 bytes per row keeps a full slice around 20KB, well under 100KB. */
        private const val CHUNK = 350

        private fun sliceTemplate(domain: String, offset: Int): String = """
            {%- set ns = namespace(items=[]) -%}
            {%- for s in (states | selectattr('domain', 'eq', '$domain') | list)[$offset:${offset + CHUNK}] -%}
            {%- set ns.items = ns.items + [[s.entity_id, s.name]] -%}
            {%- endfor -%}
            {{ ns.items | tojson }}
        """.trimIndent()
    }
}
