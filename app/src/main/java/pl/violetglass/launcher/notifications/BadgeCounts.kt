package pl.violetglass.launcher.notifications

/** Metadata only: no notification text, actions, people, messages or extras. */
data class NotificationMark(val key: String, val packageName: String, val userId: Int,
    val group: String = "", val summary: Boolean = false, val ongoing: Boolean = false,
    val allowed: Boolean = true, val number: Int = 0, val message: Boolean = false)

data class PetNotificationSources(val all: Map<String, Int> = emptyMap(), val messages: Map<String, Int> = emptyMap())

fun isMessageNotification(category: String?, template: String?): Boolean =
    category == "msg" || category == "email" || template?.endsWith("\$MessagingStyle") == true

fun petNotificationSources(marks: List<NotificationMark>, userId: Int): PetNotificationSources {
    // A group header can survive cancellation of every message it used to group.
    val eligible = marks.filterNot { it.summary }.map { it.copy(allowed = true) }
    return PetNotificationSources(countBadges(eligible, userId), countBadges(eligible.filter { it.message }, userId))
}

fun countBadges(marks: List<NotificationMark>, userId: Int): Map<String, Int> {
    val accepted = marks.asSequence().filter { it.userId == userId && it.allowed && !it.ongoing }
        .distinctBy { it.key }.take(2000).toList()
    val childGroups = accepted.filter { !it.summary && it.group.isNotEmpty() }.map { it.packageName to it.group }.toSet()
    return buildMap {
        accepted.filter { !it.summary || (it.packageName to it.group) !in childGroups }.forEach {
            put(it.packageName, ((get(it.packageName) ?: 0) + it.number.coerceIn(1, 999)).coerceAtMost(999))
        }
    }
}
