package pl.violetglass.launcher.ha

import org.junit.Assert.*
import org.junit.Test

class HaNavigationTest {
    @Test fun mapsIntentBecomesOnlyASafeUrlAndDoesNotForwardArbitraryComponents() {
        assertEquals("geo:0,0?q=Warszawa", resolveHaExternalTarget("intent:0,0?q=Warszawa#Intent;scheme=geo;package=com.google.android.apps.maps;end"))
        assertNull(resolveHaExternalTarget("intent:0,0#Intent;scheme=geo;package=evil.app;end"))
        assertNull(resolveHaExternalTarget("intent:alert(1)#Intent;scheme=javascript;package=com.google.android.apps.maps;end"))
        assertNull(resolveHaExternalTarget("intent://private#Intent;scheme=file;package=com.google.android.apps.maps;end"))
    }
}
