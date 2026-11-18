package pl.violetglass.launcher.notifications

import org.junit.Assert.*
import org.junit.Test

class PetNotificationSourceTest {
    @Test fun groupHeaderNeverKeepsTheEnvelopeButStillCountsForTheAppBadge() {
        val header = NotificationMark("header", "mail", 0, "thread", summary = true, number = 5, message = true)
        val first = NotificationMark("first", "mail", 0, "thread", message = true)
        val second = first.copy(key = "second")
        assertEquals(mapOf("mail" to 2), petNotificationSources(listOf(header, first, second), 0).messages)
        assertEquals(mapOf("mail" to 1), petNotificationSources(listOf(header, second), 0).messages)
        assertEquals(PetNotificationSources(), petNotificationSources(listOf(header), 0))
        assertEquals(mapOf("mail" to 5), countBadges(listOf(header), 0))
    }
    @Test fun technicalAndMediaNotificationsDoNotKeepTheMessageEnvelopeOn() {
        val sources = petNotificationSources(listOf(
            NotificationMark("media", "browser", 0, message = isMessageNotification("transport", null)),
            NotificationMark("service", "health", 0),
            NotificationMark("download", "browser", 0, message = isMessageNotification("progress", null))
        ), 0)
        assertFalse(sources.all.isEmpty())
        assertTrue(sources.messages.isEmpty())
    }
    @Test fun messageEmailAndMessagingStyleRemainUntilRemovedWhileOngoingAndOtherProfilesAreExcluded() {
        val chat = NotificationMark("chat", "chat.app", 0, message = isMessageNotification("msg", null))
        val mail = NotificationMark("mail", "mail.app", 0, message = isMessageNotification("email", null))
        val legacyChat = NotificationMark("legacy", "chat.old", 0, message = isMessageNotification(null, "android.app.Notification\$MessagingStyle"))
        val sources = petNotificationSources(listOf(chat, mail, legacyChat,
            chat.copy(key = "ongoing", ongoing = true), chat.copy(key = "work", userId = 10)), 0)
        assertEquals(mapOf("chat.app" to 1, "mail.app" to 1, "chat.old" to 1), sources.messages)
        assertTrue(petNotificationSources(emptyList(), 0).messages.isEmpty())
    }
}
