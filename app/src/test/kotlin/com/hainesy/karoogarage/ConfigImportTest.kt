package com.hainesy.karoogarage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The .kgcfg import (#6). The import activity is exported, so any app on the device can hand
 * it any intent; a config it accepts replaces the server, credential and the physical action
 * the garage button fires. These pin what gets refused, and that nothing is written until
 * the rider confirms.
 */
class ConfigImportTest {

    private val ownDir = "/storage/emulated/0/Android/data/com.hainesy.karoogarage/files"
    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ4In0.c2lnbmF0dXJl"

    private fun cfg(
        baseUrl: String = "https://home.example.com",
        token: String = this.token,
        entityId: String = "cover.garage_door",
        domain: String = "cover",
        service: String = "toggle",
    ) = """{"baseUrl":"$baseUrl","token":"$token","entityId":"$entityId","domain":"$domain","service":"$service"}"""

    private fun rejected(block: () -> Unit): String {
        try {
            block()
        } catch (e: ConfigImport.Rejected) {
            return e.message.orEmpty()
        }
        fail("expected the import to be refused")
        error("unreachable")
    }

    private fun check(
        action: String? = ConfigImport.ACTION_VIEW,
        scheme: String? = "content",
        path: String? = null,
        displayName: String? = "garage.kgcfg",
    ) = ConfigImport.checkSource(action, scheme, path, displayName, ownDir)

    // --- externally supplied intents ----------------------------------------------------

    @Test
    fun `a file-manager VIEW of a kgcfg content URI is accepted`() {
        check()
        check(displayName = "Garage.KGCFG")
    }

    @Test
    fun `push-config's file URI inside the app's own folder is accepted`() {
        check(scheme = "file", path = "$ownDir/garage.kgcfg", displayName = null)
    }

    @Test
    fun `SEND and other actions are refused`() {
        rejected { check(action = "android.intent.action.SEND") }
        rejected { check(action = null) }
        rejected { check(action = "android.intent.action.EDIT") }
    }

    @Test
    fun `web and other schemes are refused`() {
        for (scheme in listOf("https", "http", "javascript", "intent", "data", null)) {
            rejected { check(scheme = scheme) }
        }
    }

    @Test
    fun `content URIs that are not kgcfg files are refused`() {
        rejected { check(displayName = "secrets.json") }
        rejected { check(displayName = "garage.kgcfg.txt") }
        rejected { check(displayName = null) }
    }

    @Test
    fun `file URIs outside the app's own folder are refused`() {
        rejected { check(scheme = "file", path = "/sdcard/Download/garage.kgcfg") }
        rejected { check(scheme = "file", path = "$ownDir/../../other.app/files/garage.kgcfg") }
        rejected { check(scheme = "file", path = "${ownDir}evil/garage.kgcfg") }
        rejected { check(scheme = "file", path = "$ownDir/garage.json") }
        rejected { check(scheme = "file", path = null) }
        rejected { ConfigImport.checkSource(ConfigImport.ACTION_VIEW, "file", "$ownDir/garage.kgcfg", null, null) }
    }

    // --- size bound ---------------------------------------------------------------------

    @Test
    fun `a file at the limit is read whole`() {
        val bytes = ByteArray(ConfigImport.MAX_BYTES) { 'a'.code.toByte() }
        assertArrayEquals(bytes, ConfigImport.readBounded(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `one byte over the limit is refused`() {
        val bytes = ByteArray(ConfigImport.MAX_BYTES + 1)
        assertEquals("file is too large", rejected { ConfigImport.readBounded(ByteArrayInputStream(bytes)) })
    }

    @Test
    fun `an endless stream is refused after reading only limit plus one bytes`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'x'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('x'.code.toByte(), off, off + len); served += len; return len
            }
        }
        rejected { ConfigImport.readBounded(endless) }
        assertEquals(ConfigImport.MAX_BYTES + 1L, served)
    }

    // --- malformed content --------------------------------------------------------------

    @Test
    fun `a valid config parses and normalises`() {
        val config = ConfigImport.parse(cfg(baseUrl = "https://home.example.com/").toByteArray())
        assertEquals(Config("https://home.example.com", token, "cover.garage_door", "cover", "toggle"), config)
    }

    @Test
    fun `malformed json, wrong types, unknown keys and missing fields are refused`() {
        val cases = listOf(
            "",
            "not json",
            "[]",
            """{"baseUrl":"https://h.example.com","token":"t","entityId":"cover.x"""", // truncated
            """{"baseUrl":"https://h.example.com","entityId":"cover.x"}""", // no token
            """{"baseUrl":"https://h.example.com","token":42,"entityId":"cover.x"}""",
            cfg().dropLast(1) + ""","extra":"x"}""",
        )
        for (case in cases) rejected { ConfigImport.parse(case.toByteArray()) }
    }

    @Test
    fun `invalid UTF-8 is refused`() {
        val bytes = cfg().toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        rejected { ConfigImport.parse(bytes) }
    }

    @Test
    fun `parse errors never echo the file, which holds the token`() {
        val message = rejected { ConfigImport.parse((cfg().dropLast(1) + ""","x":1}""").toByteArray()) }
        assertFalse(message.contains(token))
        val badToken = rejected { ConfigImport.parse(cfg(token = "abc def").toByteArray()) }
        assertFalse(badToken.contains("abc"))
    }

    @Test
    fun `server URLs that are not a plain http(s) address are refused`() {
        for (url in listOf(
            "ftp://home.example.com",
            "javascript:alert(1)",
            "https://",
            "https://user:pass@home.example.com",
            "https://home.example.com/?next=evil",
            "https://home.example.com/#x",
            "home.example.com",
        )) {
            rejected { ConfigImport.parse(cfg(baseUrl = url).toByteArray()) }
        }
    }

    @Test
    fun `LAN addresses main accepted still import, including underscore hostnames`() {
        for ((url, origin) in listOf(
            "http://homeassistant.local:8123" to "http://homeassistant.local:8123",
            "http://192.168.1.10:8123" to "http://192.168.1.10:8123",
            "http://[fe80::1]:8123" to "http://[fe80::1]:8123",
            "http://my_ha:8123" to "http://my_ha:8123",
            "https://HA_Server.lan" to "https://ha_server.lan",
        )) {
            assertEquals(origin, ConfigImport.Pending(ConfigImport.parse(cfg(baseUrl = url).toByteArray())).summary.origin)
        }
    }

    @Test
    fun `the underscore-hostname path refuses anything but host and port`() {
        for (url in listOf(
            "http://my_ha:99999",
            "http://my_ha:0",
            "http://my_ha:abc",
            "http://user@my_ha:8123",
            "http://my_ha;x=1",
            "http://my_ha:8123/?q=1",
        )) {
            rejected { ConfigImport.parse(cfg(baseUrl = url).toByteArray()) }
        }
    }

    @Test
    fun `entity, domain and service must be Home Assistant slugs`() {
        rejected { ConfigImport.parse(cfg(entityId = "garage_door").toByteArray()) }
        rejected { ConfigImport.parse(cfg(entityId = "cover.garage door").toByteArray()) }
        rejected { ConfigImport.parse(cfg(entityId = "cover.Garage").toByteArray()) }
        rejected { ConfigImport.parse(cfg(domain = "../api").toByteArray()) }
        rejected { ConfigImport.parse(cfg(service = "toggle/../../config").toByteArray()) }
    }

    @Test
    fun `a token that could break the Authorization header is refused`() {
        rejected { ConfigImport.parse(cfg(token = "abc\\r\\nX-Evil: 1").toByteArray()) }
        rejected { ConfigImport.parse(cfg(token = "abc def").toByteArray()) }
    }

    // --- confirmation -------------------------------------------------------------------

    @Test
    fun `the summary shows host, entity and service but never the token`() {
        val pending = ConfigImport.Pending(
            ConfigImport.parse(cfg(baseUrl = "https://Home.Example.com:8123/ha").toByteArray()),
        )
        assertEquals(
            ConfigImport.Summary("https://home.example.com:8123", "cover.garage_door", "cover.toggle"),
            pending.summary,
        )
        assertFalse(pending.summary.toString().contains(token))
    }

    private class FakeStore(var state: GarageState?) : ConfigImport.Target {
        val saved = mutableListOf<Config>()
        override fun load(): GarageState? = state
        override fun save(config: Config) { saved += config }
    }

    private fun oauth(baseUrl: String = "https://old.example.com", refresh: String? = "refresh-1") =
        GarageState(
            baseUrl = baseUrl, entityId = "cover.old", entityName = null, domain = "cover",
            service = "toggle", authMode = AuthMode.OAUTH, token = null, refreshToken = refresh,
            accessToken = "access", accessTokenExpiresAt = 0L, accountName = "me",
        )

    private fun pending() = ConfigImport.Pending(ConfigImport.parse(cfg().toByteArray()))

    @Test
    fun `parsing and showing the dialog writes nothing`() {
        val store = FakeStore(oauth())
        val pending = pending()
        assertEquals("https://old.example.com", pending.replacing(store))
        // Cancel = the activity drops the Pending without calling confirm.
        assertTrue(store.saved.isEmpty())
        assertEquals(oauth(), store.state)
    }

    @Test
    fun `confirming over an OAuth login saves and hands back the session to revoke`() {
        val store = FakeStore(oauth())
        val replaced = pending().confirm(store)
        assertEquals(listOf(pending().config), store.saved)
        assertEquals(ConfigImport.Session("https://old.example.com", "refresh-1"), replaced)
    }

    @Test
    fun `confirming over a legacy token or a fresh install revokes nothing`() {
        val legacy = oauth().copy(authMode = AuthMode.LEGACY_TOKEN, token = "old", refreshToken = null)
        assertNull(pending().confirm(FakeStore(legacy)))
        assertNull(pending().confirm(FakeStore(null)))
        assertNull(pending().confirm(FakeStore(oauth(refresh = null))))
        assertNull(pending().replacing(FakeStore(null)))
    }
}
