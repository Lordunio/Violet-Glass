package pl.violetglass.launcher.icons

import org.junit.Assert.*
import org.junit.Test

class IconChoiceTest {
    @Test fun individualSystemIconIsDifferentFromNoOverride() {
        val encoded = encodeIconChoice("0:com.example/.Main", IconChoice())
        assertEquals(IconChoice(), decodeIconChoices(setOf(encoded))["0:com.example/.Main"])
        assertNull(decodeIconChoices(emptySet())["0:com.example/.Main"])
    }
    @Test fun referencesRoundTripAndRejectForeignPathsAndControlCharacters() {
        val choice = IconChoice("com.example.pack", "mail_icon")
        assertEquals(choice, decodeIconChoices(setOf(encodeIconChoice("0:com.example/.Main", choice))).values.single())
        assertFalse(IconChoice("com.example.pack", "../secret").valid())
        assertFalse(IconChoice("", "mail").valid())
        assertTrue(decodeIconChoices(setOf("bad", "0:app\tpack\tdrawable", "0:app\tcom.example.pack\t../secret")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { encodeIconChoice("bad\nkey", choice) }
    }
}
