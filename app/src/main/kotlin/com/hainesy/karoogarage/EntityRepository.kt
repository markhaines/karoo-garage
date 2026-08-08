package com.hainesy.karoogarage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
        val body = buildJsonObject {
            put("template", TEMPLATE)
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
            }.sortedWith(
                compareBy(
                    { DOMAIN_ORDER.indexOf(it.domain).let { i -> if (i < 0) DOMAIN_ORDER.size else i } },
                    { it.friendlyName.lowercase() },
                ),
            )
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

        private val TEMPLATE = """
            {%- set ns = namespace(items=[]) -%}
            {%- for s in states -%}
            {%- if s.domain in ${PICKABLE_DOMAINS.joinToString(",", "[", "]") { "'$it'" }} -%}
            {%- set ns.items = ns.items + [[s.entity_id, s.name]] -%}
            {%- endif -%}
            {%- endfor -%}
            {{ ns.items | tojson }}
        """.trimIndent()
    }
}
