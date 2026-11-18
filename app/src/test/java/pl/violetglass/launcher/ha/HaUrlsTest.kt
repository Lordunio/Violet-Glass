package pl.violetglass.launcher.ha

import org.junit.Assert.*
import org.junit.Test

class HaUrlsTest {
    @Test fun mapLinksAreHandledButExecutableAndFileSchemesAreRejected() {
        assertTrue(HaUrls.external("geo:0,0?q=Warszawa"))
        assertTrue(HaUrls.external("google.navigation:q=52.2,21.0"))
        assertTrue(HaUrls.external("https://maps.google.com/?q=Warszawa"))
        assertFalse(HaUrls.external("javascript:alert(1)"))
        assertFalse(HaUrls.external("file:///data/private"))
        assertFalse(HaUrls.external("intent://arbitrary#Intent;component=evil/.Activity;end"))
    }
    @Test fun freshInstallationRequiresAnExplicitEndpoint() {
        assertFalse(HaSettings().enabled)
        assertNull(HaUrls.target(HaSettings()))
    }
    @Test fun remoteHttpsKeepsProxyPrefixAndDashboardView() {
        assertEquals("https://ha.example.com/proxy/dashboard/main", HaUrls.target(HaSettings(
            remoteUrl = "https://ha.example.com/proxy/", dashboardPath = "/dashboard/main", connection = HaConnection.REMOTE)))
    }
    @Test fun credentialsAndTokensAreNotAcceptedAsSettings() {
        listOf("https://user:pass@ha.example.com", "https://ha.example.com/?token=secret", "https://ha.example.com/#secret").forEach { assertNull(HaUrls.endpoint(it, true)) }
    }
    @Test fun unsafeSchemesAndPublicHttpAreRejected() {
        listOf("javascript:alert(1)", "file:///sdcard/a", "content://file", "http://public.example.com", "https://ha.example.com:0", "https://ha.example.com:99999").forEach { assertNull(HaUrls.endpoint(it, false)) }
        assertNull(HaUrls.endpoint("http://192.0.2.10:8123", true))
    }
    @Test fun pathsCannotEscapeToAnotherServerOrCarryAuthParameters() {
        listOf("//evil.example/path", "https://evil.example", "/a/../b", "/?token=a", "/#secret", "/a\\b").forEach { assertNull(HaUrls.path(it)) }
        assertEquals("/dashboard-test/view", HaUrls.path("/dashboard-test/view"))
    }
    @Test fun privateAddressesUseRealOctetBoundaries() {
        listOf("192.168.1.2", "10.0.0.1", "172.16.0.1", "172.31.255.255", "127.0.0.1", "homeassistant.local", "[fd00::1]").forEach { assertTrue(it, HaUrls.privateHost(it)) }
        listOf("192.168.1.999", "172.32.0.1", "10.0.0.1.evil.com", "192.169.1.2").forEach { assertFalse(it, HaUrls.privateHost(it)) }
    }
    @Test fun originsNormalizeDefaultPortAndKeepServersSeparate() {
        assertEquals(HaUrls.origin("https://ha.example.com:443/view"), HaUrls.origin("https://ha.example.com/"))
        assertNotEquals(HaUrls.origin("https://ha.example.com"), HaUrls.origin("http://ha.example.com"))
        assertFalse(HaUrls.safeResource("http://evil.example/resource"))
        assertTrue(HaUrls.safeResource("https://cdn.example/resource"))
    }
}
