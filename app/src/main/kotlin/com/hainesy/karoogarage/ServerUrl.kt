package com.hainesy.karoogarage

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * The one place a Home Assistant server URL is checked. Every URL the app will send
 * credentials or service calls to goes through [parse] first: typed at login, typed in the
 * legacy form, imported from a `.kgcfg`, found by mDNS, or advertised by HA as its public URL.
 *
 * The result is a canonical origin, `scheme://host[:port]`, lowercased, with no path and no
 * default port. Home Assistant does not serve from a sub-path, so any path is a mistake.
 *
 * Plain HTTP is allowed only to a [local][Origin.isLocal] destination, and callers must warn
 * the rider before sending credentials over it ([Origin.isCleartext]). See ADR-0004.
 *
 * Pure JVM, no DNS: hostnames are classified by name only, never resolved.
 */
object ServerUrl {

    enum class Reason(val detail: String) {
        // Each reads after "it", as in "the server URL ... ".
        MALFORMED("is not a valid web address"),
        SCHEME("must start with https:// (or http:// on your home network)"),
        USERINFO("must not contain a username or password"),
        PATH("must be just the server address, with no path after it"),
        QUERY_OR_FRAGMENT("must not contain ? or #"),
        PORT("has a port outside 1 to 65535"),
        CLEARTEXT_REMOTE("can only use http:// on your home network, use https://"),
    }

    class InvalidServerUrlException(val reason: Reason) :
        IllegalArgumentException("server URL ${reason.detail}")

    data class Origin(
        val scheme: String,
        /** Lowercased; IPv6 literals keep their brackets. */
        val host: String,
        /** Explicit non-default port, or -1. */
        val port: Int,
        /** Loopback, private or link-local address, or a `.local` / `.home.arpa` name. */
        val isLocal: Boolean,
    ) {
        val isCleartext: Boolean get() = scheme == "http"
        val value: String get() = if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
        override fun toString(): String = value
    }

    fun parse(raw: String): Result<Origin> = runCatching { parseOrThrow(raw) }

    /** The canonical origin string, or null if [raw] is not acceptable. */
    fun canonicalOrNull(raw: String): String? = parse(raw).getOrNull()?.value

    /**
     * HA's advertised public ("external") URL, if it is worth offering: it must pass [parse]
     * like any other URL, and must be HTTPS, because it is the address used away from home.
     */
    fun externalOrNull(raw: String): String? =
        parse(raw).getOrNull()?.takeIf { !it.isCleartext }?.value

    /**
     * The URL to offer for an instance found by mDNS. Prefers the `internal_url` HA puts in
     * its TXT record when that is HTTPS; otherwise the resolved address over HTTP, which
     * [parse] only allows when the address is local.
     *
     * @param hostAddress the resolved address as `InetAddress.hostAddress` gives it (IPv6
     *   without brackets, possibly with a `%scope` suffix, which is dropped)
     */
    fun discovered(txtInternalUrl: String?, hostAddress: String, port: Int): String? {
        txtInternalUrl?.let { parse(it).getOrNull() }
            ?.takeIf { !it.isCleartext }
            ?.let { return it.value }
        val host = hostAddress.substringBefore('%').let { if (it.contains(':')) "[$it]" else it }
        return canonicalOrNull("http://$host:$port")
    }

    private fun parseOrThrow(raw: String): Origin {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.code < 0x20 }) {
            throw InvalidServerUrlException(Reason.MALFORMED)
        }
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            throw InvalidServerUrlException(Reason.MALFORMED)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw InvalidServerUrlException(Reason.SCHEME)
        }
        if (uri.isOpaque || uri.rawAuthority.isNullOrEmpty()) {
            throw InvalidServerUrlException(Reason.MALFORMED)
        }
        // Checked before the host: "user@host" with a bad port can still parse as a
        // registry authority, and the rider should hear about the credentials first.
        if (uri.rawAuthority.contains('@')) {
            throw InvalidServerUrlException(Reason.USERINFO)
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw InvalidServerUrlException(Reason.QUERY_OR_FRAGMENT)
        }
        val path = uri.rawPath.orEmpty()
        if (path != "" && path != "/") {
            throw InvalidServerUrlException(Reason.PATH)
        }
        // java.net.URI falls back to a registry-based authority (host == null) for anything
        // it cannot read as host[:port]: a non-numeric or out-of-range port, an IPv6 zone,
        // an underscore in a name. Names with underscores (my_ha.lan, Docker and router
        // names) are real and worked before, so accept exactly that shape; the .kgcfg
        // importer in PR #11 uses the same pattern. Otherwise tell a bad port from a bad host.
        val registry = if (uri.host.isNullOrEmpty()) {
            UNDERSCORE_AUTHORITY.matchEntire(uri.rawAuthority)
                ?: throw InvalidServerUrlException(
                    if (PORT_SUFFIX.containsMatchIn(uri.rawAuthority)) Reason.PORT else Reason.MALFORMED,
                )
        } else {
            null
        }
        val host = (registry?.groupValues?.get(1) ?: uri.host).lowercase()
        if (host.contains('%')) {
            throw InvalidServerUrlException(Reason.MALFORMED)
        }
        if (uri.rawAuthority.endsWith(":")) {
            throw InvalidServerUrlException(Reason.PORT)
        }
        val rawPort = registry?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: uri.port
        val port = when (val p = rawPort) {
            -1 -> -1
            !in 1..65535 -> throw InvalidServerUrlException(Reason.PORT)
            DEFAULT_PORTS.getValue(scheme) -> -1
            else -> p
        }
        val local = isLocalHost(host)
        if (scheme == "http" && !local) {
            throw InvalidServerUrlException(Reason.CLEARTEXT_REMOTE)
        }
        return Origin(scheme, host, port, local)
    }

    /** [host] as java.net.URI reports it: lowercased, IPv6 in brackets. */
    internal fun isLocalHost(host: String): Boolean {
        if (host.startsWith("[")) {
            val literal = host.removePrefix("[").removeSuffix("]")
            // A bracketed literal is parsed, never looked up.
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
                ?: return false
            return when (address) {
                is Inet6Address -> isLocalV6(address.address)
                else -> isLocalV4(address.address) // IPv4-mapped, unwrapped by the JDK
            }
        }
        ipv4Bytes(host)?.let { return isLocalV4(it) }
        return host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".home.arpa")
    }

    private fun ipv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { i, part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val n = part.toInt()
            if (n > 255) return null
            bytes[i] = n.toByte()
        }
        return bytes
    }

    private fun isLocalV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        return a0 == 127 || // loopback
            a0 == 10 || // RFC 1918
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 169 && a1 == 254) // link-local
    }

    private fun isLocalV6(b: ByteArray): Boolean {
        val loopback = b.sliceArray(0..14).all { it == 0.toByte() } && b[15] == 1.toByte()
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        return loopback ||
            (a0 and 0xfe) == 0xfc || // fc00::/7 unique local
            (a0 == 0xfe && (a1 and 0xc0) == 0x80) // fe80::/10 link-local
    }

    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
    private val PORT_SUFFIX = Regex(":[^\\]]*$")
    private val UNDERSCORE_AUTHORITY =
        Regex("^([A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)(?::([0-9]{1,5}))?$")
}
