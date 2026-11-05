package com.hainesy.karoogarage

import com.hainesy.karoogarage.ServerUrl.InvalidServerUrlException
import com.hainesy.karoogarage.ServerUrl.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every URL the app sends a password, a token or a service call to passes through
 * [ServerUrl.parse]. A false accept here sends credentials somewhere the rider did not
 * mean, or in the clear over the internet. Issue #9.
 */
class ServerUrlTest {

    private fun ok(raw: String): ServerUrl.Origin =
        ServerUrl.parse(raw).getOrElse { throw AssertionError("expected $raw to be accepted, got $it") }

    private fun rejected(raw: String): Reason {
        val error = ServerUrl.parse(raw).exceptionOrNull()
            ?: throw AssertionError("expected $raw to be rejected")
        return (error as InvalidServerUrlException).reason
    }

    // --- canonical origin ---------------------------------------------------------------

    @Test
    fun `https origin is accepted and canonicalised`() {
        assertEquals("https://ha.example.com", ok("https://ha.example.com").value)
        assertEquals("https://ha.example.com", ok("  HTTPS://HA.Example.COM/  ").value)
        assertEquals("https://ha.example.com:8443", ok("https://ha.example.com:8443").value)
    }

    @Test
    fun `default ports are dropped`() {
        assertEquals("https://ha.example.com", ok("https://ha.example.com:443").value)
        assertEquals("http://192.168.1.20", ok("http://192.168.1.20:80").value)
        assertEquals("http://192.168.1.20:8123", ok("http://192.168.1.20:8123").value)
    }

    @Test
    fun `a remote https origin is neither local nor cleartext`() {
        val origin = ok("https://ha.example.com")
        assertFalse(origin.isLocal)
        assertFalse(origin.isCleartext)
    }

    @Test
    fun `canonicalOrNull returns null for a rejected URL`() {
        assertEquals("https://ha.example.com", ServerUrl.canonicalOrNull("https://ha.example.com/"))
        assertNull(ServerUrl.canonicalOrNull("http://ha.example.com"))
    }

    // --- malformed ----------------------------------------------------------------------

    @Test
    fun `malformed URLs are rejected`() {
        assertEquals(Reason.MALFORMED, rejected(""))
        assertEquals(Reason.MALFORMED, rejected("   "))
        assertEquals(Reason.MALFORMED, rejected("https://"))
        assertEquals(Reason.MALFORMED, rejected("https:ha.example.com"))
        assertEquals(Reason.MALFORMED, rejected("https://ha example.com"))
        assertEquals(Reason.MALFORMED, rejected("https://ha.exa\tmple.com"))
        assertEquals(Reason.MALFORMED, rejected("https://ha.example.com\u0000"))
        assertEquals(Reason.MALFORMED, rejected("https://-bad.example.com"))
        assertEquals(Reason.MALFORMED, rejected("https://[::1"))
        assertEquals(Reason.MALFORMED, rejected("http://[fe80::1%25wlan0]:8123"))
    }

    @Test
    fun `anything but http and https is rejected`() {
        assertEquals(Reason.SCHEME, rejected("ha.example.com"))
        assertEquals(Reason.SCHEME, rejected("ftp://ha.example.com"))
        assertEquals(Reason.SCHEME, rejected("javascript://ha.example.com"))
        assertEquals(Reason.SCHEME, rejected("file:///etc/passwd"))
    }

    @Test
    fun `userinfo is rejected`() {
        assertEquals(Reason.USERINFO, rejected("https://user:pass@ha.example.com"))
        assertEquals(Reason.USERINFO, rejected("https://user@ha.example.com"))
        // The classic confusion: looks like example.com, is evil.test.
        assertEquals(Reason.USERINFO, rejected("https://ha.example.com@evil.test"))
    }

    @Test
    fun `query and fragment are rejected`() {
        assertEquals(Reason.QUERY_OR_FRAGMENT, rejected("https://ha.example.com?x=1"))
        assertEquals(Reason.QUERY_OR_FRAGMENT, rejected("https://ha.example.com/?"))
        assertEquals(Reason.QUERY_OR_FRAGMENT, rejected("https://ha.example.com#frag"))
    }

    @Test
    fun `any path is rejected`() {
        assertEquals(Reason.PATH, rejected("https://ha.example.com/api"))
        assertEquals(Reason.PATH, rejected("https://ha.example.com/ha/"))
        assertEquals(Reason.PATH, rejected("https://ha.example.com//"))
    }

    @Test
    fun `underscore hostnames are accepted, as before, under the same http rule`() {
        // java.net.URI gives these no host; Docker and router names use them.
        assertEquals("https://my_ha.example.com:8443", ok("https://My_HA.example.com:8443/").value)
        assertEquals("http://my_ha.local:8123", ok("http://my_ha.local:8123").value)
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://my_ha:8123"))
        assertEquals(Reason.PORT, rejected("https://my_ha.example.com:70000"))
        assertEquals(Reason.PORT, rejected("https://my_ha.example.com:0"))
        assertEquals(Reason.USERINFO, rejected("https://u@my_ha.example.com"))
    }

    @Test
    fun `invalid ports are rejected`() {
        assertEquals(Reason.PORT, rejected("https://ha.example.com:0"))
        assertEquals(Reason.PORT, rejected("https://ha.example.com:65536"))
        assertEquals(Reason.PORT, rejected("https://ha.example.com:99999999999"))
        assertEquals(Reason.PORT, rejected("https://ha.example.com:abc"))
        assertEquals(Reason.PORT, rejected("https://ha.example.com:"))
        assertEquals(Reason.PORT, rejected("http://[::1]:70000"))
        assertEquals("https://ha.example.com:65535", ok("https://ha.example.com:65535").value)
    }

    // --- remote http --------------------------------------------------------------------

    @Test
    fun `http to a public name or address is rejected`() {
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://ha.example.com"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://ha.example.com:8123"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://8.8.8.8:8123"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://[2001:db8::1]:8123"))
    }

    @Test
    fun `http to an address just outside the private ranges is rejected`() {
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://172.15.0.1"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://172.32.0.1"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://192.169.0.1"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://100.64.0.1")) // CGNAT is not the LAN
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://[fec0::1]")) // deprecated site-local
    }

    @Test
    fun `a single-label name is not assumed local`() {
        // It goes through whatever DNS the network hands out, so it is not provably local.
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://homeassistant:8123"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://local:8123"))
    }

    @Test
    fun `a name that only looks like an address is not local`() {
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://192.168.1.1.example.com"))
        assertEquals(Reason.CLEARTEXT_REMOTE, rejected("http://10.0.0.1.nip.io"))
    }

    // --- local http ---------------------------------------------------------------------

    @Test
    fun `http to private IPv4 is accepted and flagged cleartext`() {
        for (raw in listOf(
            "http://10.0.0.5:8123",
            "http://172.16.0.1:8123",
            "http://172.31.255.254:8123",
            "http://192.168.1.20:8123",
            "http://127.0.0.1:8123",
            "http://169.254.10.1:8123",
        )) {
            val origin = ok(raw)
            assertTrue(raw, origin.isLocal)
            assertTrue(raw, origin.isCleartext)
            assertEquals(raw, origin.value)
        }
    }

    @Test
    fun `http to local IPv6 is accepted`() {
        assertEquals("http://[::1]:8123", ok("http://[::1]:8123").value)
        assertEquals("http://[fd12:3456::20]:8123", ok("http://[FD12:3456::20]:8123").value)
        assertEquals("http://[fe80::1]:8123", ok("http://[fe80::1]:8123").value)
        assertTrue(ok("http://[fc00::1]").isLocal)
        assertTrue(ok("http://[::ffff:192.168.1.20]:8123").isLocal)
    }

    @Test
    fun `http to mDNS and home names is accepted`() {
        assertTrue(ok("http://homeassistant.local:8123").isCleartext)
        assertTrue(ok("http://ha.home.arpa:8123").isLocal)
        assertTrue(ok("http://localhost:8123").isLocal)
    }

    @Test
    fun `https to a local address is fine and not cleartext`() {
        val origin = ok("https://192.168.1.20:8123")
        assertTrue(origin.isLocal)
        assertFalse(origin.isCleartext)
    }

    // --- callers ------------------------------------------------------------------------

    @Test
    fun `Config validation and normalisation use the same rules`() {
        fun config(url: String) = Config(baseUrl = url, token = "t", entityId = "cover.garage")
        assertTrue(config("https://ha.example.com").isValid())
        assertTrue(config("http://192.168.1.20:8123").isValid())
        assertFalse(config("http://ha.example.com").isValid())
        assertFalse(config("https://user@ha.example.com").isValid())
        assertFalse(config("https://ha.example.com/lovelace").isValid())
        assertEquals(
            "https://ha.example.com",
            config(" HTTPS://HA.example.com:443/ ").normalised().baseUrl,
        )
        // A rejected URL is left alone by normalise, so the error names what was typed.
        assertEquals("http://ha.example.com", config("http://ha.example.com/").normalised().baseUrl)
    }

    @Test
    fun `the advertised external URL gets identical validation and must be https`() {
        assertEquals("https://ha.example.com", ServerUrl.externalOrNull("https://HA.example.com/"))
        assertNull(ServerUrl.externalOrNull("http://ha.example.com"))
        assertNull(ServerUrl.externalOrNull("https://ha.example.com/path"))
        assertNull(ServerUrl.externalOrNull("https://u:p@ha.example.com"))
        assertNull(ServerUrl.externalOrNull("not a url"))
        // A local HTTP "external" URL is no use mid-ride; never offer it.
        assertNull(ServerUrl.externalOrNull("http://192.168.1.20:8123"))
    }

    @Test
    fun `discovery prefers an advertised https internal URL over the raw address`() {
        assertEquals(
            "https://ha.home.example.com",
            ServerUrl.discovered("https://ha.home.example.com/", "192.168.1.20", 8123),
        )
        assertEquals("http://192.168.1.20:8123", ServerUrl.discovered(null, "192.168.1.20", 8123))
        assertEquals(
            "http://192.168.1.20:8123",
            ServerUrl.discovered("http://homeassistant.local:8123", "192.168.1.20", 8123),
        )
        assertEquals("http://[fe80::1]:8123", ServerUrl.discovered(null, "fe80::1%wlan0", 8123))
        // A resolved address outside the LAN is not offered over http.
        assertNull(ServerUrl.discovered(null, "8.8.8.8", 8123))
    }
}
