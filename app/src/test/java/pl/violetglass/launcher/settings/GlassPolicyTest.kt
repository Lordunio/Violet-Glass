package pl.violetglass.launcher.settings

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class GlassPolicyTest {
    @Test fun globalOffRemovesEveryExpensiveEffectRegardlessOfMode() {
        GlassMode.entries.forEach { mode ->
            val policy = resolveGlass(GlassSettings(enabled = false, mode = mode, blur = 100, reflections = 100, animation = 100), GlassEnvironment())
            assertFalse(policy.enabled); assertEquals(0f, policy.blurDp); assertEquals(0f, policy.reflection); assertEquals(0, policy.durationMillis)
        }
    }
    @Test fun modesHaveDifferentBudgetsAndLiteHasNoGpuBlurOrMotion() {
        val ultra = resolveGlass(GlassSettings(mode = GlassMode.ULTRA, blur = 100), GlassEnvironment())
        val classic = resolveGlass(GlassSettings(mode = GlassMode.CLASSIC, blur = 100), GlassEnvironment())
        val lite = resolveGlass(GlassSettings(mode = GlassMode.LITE), GlassEnvironment())
        assertTrue(ultra.blurDp > classic.blurDp); assertTrue(ultra.reflection > classic.reflection)
        assertTrue(ultra.durationMillis > classic.durationMillis)
        assertEquals(0f, lite.blurDp); assertEquals(0f, lite.reflection); assertEquals(0, lite.durationMillis)
    }
    @Test fun automaticLimitsReturnToRequestedModeAfterRecovery() {
        val selected = GlassSettings(mode = GlassMode.ULTRA)
        assertEquals(GlassMode.LITE, resolveGlass(selected, GlassEnvironment(lowRam = true)).mode)
        assertEquals(GlassMode.LITE, resolveGlass(selected, GlassEnvironment(powerSave = true)).mode)
        assertEquals(GlassMode.CLASSIC, resolveGlass(selected, GlassEnvironment(thermalStatus = 2)).mode)
        assertEquals(GlassMode.LITE, resolveGlass(selected, GlassEnvironment(thermalStatus = 3)).mode)
        assertEquals(GlassMode.ULTRA, resolveGlass(selected, GlassEnvironment()).mode)
        assertEquals(GlassMode.LITE, resolveGlass(selected.copy(mode = GlassMode.LITE), GlassEnvironment(thermalStatus = 2)).mode)
    }
    @Test fun automationCanBeDisabledButHardwareAndReducedMotionAreRespected() {
        val manual = GlassSettings(mode = GlassMode.ULTRA, automatic = false)
        val policy = resolveGlass(manual, GlassEnvironment(lowRam = true, powerSave = true, thermalStatus = 4))
        assertEquals(GlassMode.ULTRA, policy.mode); assertTrue(policy.blurDp > 0)
        val unsupported = resolveGlass(manual, GlassEnvironment(hardware = false, animations = false))
        assertEquals(0f, unsupported.blurDp); assertEquals(0, unsupported.durationMillis)
    }
    @Test fun zeroSlidersDisableCorrespondingEffects() {
        val policy = resolveGlass(GlassSettings(blur = 0, reflections = 0, animation = 0), GlassEnvironment())
        assertEquals(0f, policy.blurDp); assertEquals(0f, policy.reflection); assertEquals(0, policy.durationMillis)
    }
    @Test fun malformedGlassPreferencesDoNotCorruptOldPreferences() {
        val decoded = PreferencesRepository.decode(mutablePreferencesOf(
            intPreferencesKey("glass_blur") to 1000, intPreferencesKey("glass_transparency") to -2,
            intPreferencesKey("glass_corners") to 900, stringPreferencesKey("glass_mode") to "BAD",
            stringSetPreferencesKey("glass_disabled_targets") to setOf("DOCK", "BAD"),
            intPreferencesKey("icon_size") to 56,
        ))
        assertEquals(100, decoded.glass.blur); assertEquals(0, decoded.glass.transparency)
        assertEquals(48, decoded.glass.corners); assertEquals(GlassMode.CLASSIC, decoded.glass.mode)
        assertEquals(setOf(GlassTarget.DOCK), decoded.glass.disabledTargets); assertEquals(56, decoded.iconSize)
    }
    @Test fun oldPreferencesReceiveDefaultsWithoutChangingLayoutSettings() {
        val value = PreferencesRepository.decode(mutablePreferencesOf(booleanPreferencesKey("locked") to true))
        assertEquals(GlassSettings(), value.glass); assertTrue(value.locked)
    }
}
