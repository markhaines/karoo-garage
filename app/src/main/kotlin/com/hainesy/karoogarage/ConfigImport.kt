package com.hainesy.karoogarage

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The checks behind [ImportConfigActivity], kept free of Android types so they run as plain
 * unit tests. The activity is exported (a file manager has to be able to hand it a .kgcfg),
 * so anything on the device can launch it with any intent. A .kgcfg replaces the live host,
 * credential and physical action, so it is treated as untrusted input: the source is checked,
 * the read is bounded, the content is parsed strictly, and nothing is written until the rider
 * confirms what they are about to point the garage button at. See ADR-0002.
 */
object ConfigImport {

    /** Generous: a real config with a long-lived token is well under 1 KB. */
    const val MAX_BYTES = 8 * 1024

    const val FILE_EXTENSION = ".kgcfg"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Why an import was refused. Messages are shown to the rider and never carry file content. */
    class Rejected(message: String) : IOException(message)

    /** Where the stored config is read from and written to. [ConfigStore] in the app. */
    interface Target {
        fun load(): GarageState?
        fun save(config: Config)
    }

    /** What the confirmation dialog shows. Deliberately has no token field. */
    data class Summary(val origin: String, val entityId: String, val action: String)

    /** An OAuth session that an import is about to drop, to revoke on the server. */
    data class Session(val baseUrl: String, val refreshToken: String)

    /**
     * Accepts only a VIEW of a `.kgcfg` file, from a `content://` URI (a file manager tap) or a
     * `file://` URI inside the app's own external-files directory (`tools/push-config.sh`).
     * Anything else, including SEND, other schemes and `file://` paths elsewhere, is refused.
     *
     * @param path the URI path, for `file://`
     * @param displayName the provider's display name for `content://` (falls back to the last
     *   path segment when the provider gives none)
     * @param ownFilesDir the app's external-files directory, null if unavailable
     */
    fun checkSource(
        action: String?,
        scheme: String?,
        path: String?,
        displayName: String?,
        ownFilesDir: String?,
    ) {
        if (action != ACTION_VIEW) throw Rejected("unsupported request")
        when (scheme?.lowercase()) {
            "content" -> {
                val name = displayName ?: throw Rejected("not a .kgcfg file")
                requireKgcfg(name)
            }
            "file" -> {
                if (path.isNullOrEmpty() || ownFilesDir.isNullOrEmpty()) {
                    throw Rejected("file is outside the app's folder")
                }
                // Canonical paths, so "files/../../" tricks and symlinks out are caught.
                val file = File(path).canonicalFile
                val dir = File(ownFilesDir).canonicalFile
                if (!file.path.startsWith(dir.path + File.separator)) {
                    throw Rejected("file is outside the app's folder")
                }
                requireKgcfg(file.name)
            }
            else -> throw Rejected("unsupported source")
        }
    }

    private fun requireKgcfg(name: String) {
        if (!name.lowercase().endsWith(FILE_EXTENSION)) throw Rejected("not a .kgcfg file")
    }

    /** Reads at most [maxBytes]; one byte more and the whole file is refused. */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        val buffer = ByteArray(maxBytes + 1)
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        if (total > maxBytes) throw Rejected("file is too large")
        return buffer.copyOf(total)
    }

    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val SLUG = Regex("^[a-z0-9_]+$")

    /** Visible ASCII only: the token goes into an Authorization header. */
    private val TOKEN = Regex("^[\\x21-\\x7e]+$")

    /**
     * Strict UTF-8, strict JSON, then the same shape rules Home Assistant itself enforces.
     * Errors never include the parser's message: kotlinx-serialization quotes the input,
     * and the input contains the token.
     */
    fun parse(bytes: ByteArray): Config {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw Rejected("invalid config file")
        }
        val config = try {
            json.decodeFromString<Config>(text).normalised()
        } catch (e: Exception) {
            throw Rejected("invalid config file")
        }
        originOf(config.baseUrl) // validates the URL
        if (config.entityId.length > 255 || !ENTITY_ID.matches(config.entityId)) {
            throw Rejected("invalid entity ID")
        }
        if (!SLUG.matches(config.domain) || !SLUG.matches(config.service)) {
            throw Rejected("invalid service")
        }
        if (config.token.length > 4096 || !TOKEN.matches(config.token)) {
            throw Rejected("invalid token")
        }
        return config
    }

    /** `scheme://host[:port]`. Refuses anything that is not a plain http(s) server address. */
    fun originOf(baseUrl: String): String {
        val uri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            throw Rejected("invalid server URL")
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            throw Rejected("invalid server URL")
        }
        val host = uri.host
        if (!host.isNullOrEmpty()) {
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "$scheme://${host.lowercase()}$port"
        }
        // java.net.URI only parses RFC 2396 hostnames. One with an underscore (my_ha.lan, a
        // Docker or router name) comes back with no host, only a raw authority. Main accepted
        // those, so accept exactly that shape here: host characters and an optional port.
        val match = UNDERSCORE_AUTHORITY.matchEntire(uri.rawAuthority.orEmpty())
            ?: throw Rejected("invalid server URL")
        val port = match.groupValues[2]
        if (port.isNotEmpty() && port.toInt() !in 1..65535) throw Rejected("invalid server URL")
        return "$scheme://${match.groupValues[1].lowercase()}${if (port.isEmpty()) "" else ":$port"}"
    }

    private val UNDERSCORE_AUTHORITY =
        Regex("^([A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)(?::([0-9]{1,5}))?$")

    fun summarise(config: Config): Summary = Summary(
        origin = originOf(config.baseUrl),
        entityId = config.entityId,
        action = "${config.domain}.${config.service}",
    )

    /**
     * A parsed config held in memory until the rider decides. [cancel] (or simply dropping
     * the object) leaves the stored config untouched; only [confirm] writes.
     */
    class Pending(val config: Config) {
        val summary: Summary = summarise(config)

        /** The origin of the config this would replace, for the dialog. Null if none. */
        fun replacing(target: Target): String? =
            target.load()?.baseUrl?.let { runCatching { originOf(it) }.getOrNull() ?: it }

        /**
         * Saves the import and returns the OAuth session it displaced, if any, so the caller
         * can revoke it on the server. Local state is written first and unconditionally, so it
         * cannot depend on a network call.
         */
        fun confirm(target: Target): Session? {
            val previous = target.load()
            target.save(config)
            val refresh = previous?.refreshToken
            return if (previous?.authMode == AuthMode.OAUTH && !refresh.isNullOrBlank()) {
                Session(previous.baseUrl, refresh)
            } else {
                null
            }
        }
    }
}
