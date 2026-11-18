package pl.violetglass.launcher.settings

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class PreferencesTest {
    @Test fun defaultsLeaveLayoutUnlockedAndDeviceAdminUnused() {
        val value = PreferencesRepository.decode(emptyPreferences())
        assertFalse(value.locked)
        assertFalse(value.autoRotate)
        assertTrue(value.gestures.values.none { it.action == GestureAction.LOCK })
        assertEquals(GestureAction.APPS, value.gestures.getValue(DesktopGesture.SWIPE_UP).action)
    }
    @Test fun malformedValuesAreClampedAndUnknownEnumsUseSafeDefaults() {
        val p = mutablePreferencesOf(intPreferencesKey("icon_size") to 1000,
            intPreferencesKey("drawer_columns") to -50, stringPreferencesKey("theme") to "BROKEN")
        val value = PreferencesRepository.decode(p)
        assertEquals(72, value.iconSize)
        assertEquals(3, value.drawerColumns)
        assertEquals(ThemeMode.SYSTEM, value.theme)
    }
    @Test fun gestureTargetAndHiddenAppsAreIndependent() {
        val p = mutablePreferencesOf(stringPreferencesKey("gesture_DOUBLE_TAP_action") to "SHORTCUT",
            stringPreferencesKey("gesture_DOUBLE_TAP_app") to "0:package/Class",
            stringPreferencesKey("gesture_DOUBLE_TAP_shortcut") to "route", stringSetPreferencesKey("hidden_apps") to setOf("hidden"))
        val value = PreferencesRepository.decode(p)
        assertEquals("route", value.gestures.getValue(DesktopGesture.DOUBLE_TAP).shortcutId)
        assertEquals(setOf("hidden"), value.hiddenApps)
        assertEquals(GestureAction.SETTINGS, value.gestures.getValue(DesktopGesture.LONG_PRESS).action)
    }
}
