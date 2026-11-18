package pl.violetglass.launcher.notifications

import org.junit.Assert.*
import org.junit.Test

class BadgeCountsTest {
    @Test fun summariesDoNotDoubleCountChildrenAndFallbackSummaryUsesAppNumber() {
        val values = listOf(NotificationMark("s", "mail", 0, "g", summary = true, number = 8),
            NotificationMark("a", "mail", 0, "g"), NotificationMark("b", "mail", 0, "g"))
        assertEquals(mapOf("mail" to 2), countBadges(values, 0))
        assertEquals(mapOf("mail" to 8), countBadges(values.take(1), 0))
    }
    @Test fun ongoingDisabledForeignProfilesAndDuplicateKeysAreExcluded() {
        val active = NotificationMark("a", "chat", 0, number = 3)
        assertEquals(mapOf("chat" to 3), countBadges(listOf(active, active,
            NotificationMark("b", "other", 0, ongoing = true), NotificationMark("c", "other", 0, allowed = false),
            NotificationMark("d", "work", 10)), 0))
    }
    @Test fun countsAreBoundedAndMissingNumbersStillShowANotification() {
        assertEquals(mapOf("mail" to 999), countBadges(List(2001) { NotificationMark("$it", "mail", 0, number = Int.MAX_VALUE) }, 0))
        assertEquals(mapOf("chat" to 1), countBadges(listOf(NotificationMark("a", "chat", 0, number = -3)), 0))
        assertTrue(countBadges(emptyList(), 0).isEmpty())
    }
}
