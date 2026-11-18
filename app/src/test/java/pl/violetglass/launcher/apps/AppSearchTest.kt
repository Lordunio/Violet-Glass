package pl.violetglass.launcher.apps

import org.junit.Assert.*
import org.junit.Test

class AppSearchTest {
    @Test fun polishDiacriticsAndCase() { assertTrue(AppSearch.matches("Łączność", "com.example", " LACZNOSC ")) }
    @Test fun searchByPackage() { assertTrue(AppSearch.matches("Ustawienia", "com.android.settings", "android.settings")) }
    @Test fun multipleWordsMustAllMatch() {
        assertTrue(AppSearch.matches("Moje Zdjęcia", "com.photos", "zdjecia moje"))
        assertFalse(AppSearch.matches("Moje Zdjęcia", "com.photos", "zdjecia aparat"))
    }
    @Test fun emptySearchMatchesAll() { assertTrue(AppSearch.matches("Telefon", "com.phone", "  ")) }
    @Test fun unrelatedSearchDoesNotMatch() { assertFalse(AppSearch.matches("Telefon", "com.phone", "kalendarz")) }
}
