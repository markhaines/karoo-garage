package com.hainesy.karoogarage

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val baseUrl: String,
    val token: String,
    val entityId: String,
    val domain: String = DEFAULT_DOMAIN,
    val service: String = DEFAULT_SERVICE,
) {
    fun isValid(): Boolean =
        ServerUrl.parse(baseUrl).isSuccess &&
            token.isNotBlank() &&
            entityId.isNotBlank() &&
            domain.isNotBlank() &&
            service.isNotBlank()

    fun normalised(): Config =
        copy(
            baseUrl = baseUrl.trim().let { ServerUrl.canonicalOrNull(it) ?: it.trimEnd('/') },
            token = token.trim(),
            entityId = entityId.trim(),
            domain = domain.trim(),
            service = service.trim(),
        )

    companion object {
        const val DEFAULT_DOMAIN = "cover"
        const val DEFAULT_SERVICE = "toggle"
    }
}
