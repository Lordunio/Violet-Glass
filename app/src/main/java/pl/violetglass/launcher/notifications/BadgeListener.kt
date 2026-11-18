package pl.violetglass.launcher.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pl.violetglass.launcher.settings.PreferencesRepository
import pl.violetglass.launcher.settings.launcherDataStore
import java.lang.ref.WeakReference

object BadgeStore {
    private val posted = MutableStateFlow(0L)
    val notificationSequence = posted.asStateFlow()
    private val activeMessages = MutableStateFlow(PetNotificationSources())
    val petMessages = activeMessages.asStateFlow()
    internal fun messages(value: PetNotificationSources) { activeMessages.value = value }
    internal fun newNotification() { posted.value++ }
    private val mutable = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts = mutable.asStateFlow()
    private val connection = MutableStateFlow(false)
    val connected = connection.asStateFlow()
    internal var listener = WeakReference<BadgeListener>(null)
    internal fun publish(value: Map<String, Int>) { mutable.value = value }
    internal fun connection(value: Boolean) { connection.value = value; if(!value) { publish(emptyMap()); messages(PetNotificationSources()) } }
    fun hasAccess(context: Context) = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, BadgeListener::class.java))
    fun refreshAccess(context: Context) {
        if(!hasAccess(context)) { connection(false); return }
        val service = listener.get()
        if(service != null && connected.value) service.refresh()
        else runCatching { NotificationListenerService.requestRebind(ComponentName(context, BadgeListener::class.java)) }
    }
}

/** Android binds this only after the user enables Notification access in system settings. */
class BadgeListener : NotificationListenerService() {
    private val seen = mutableSetOf<String>()
    private val removedKeys = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enabled = false
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            PreferencesRepository(applicationContext.launcherDataStore).data.map { it.badgesEnabled }.distinctUntilChanged()
                .catch { emit(false) }.collect { enabled = it; refresh() }
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        removedKeys.clear()
        BadgeStore.listener = WeakReference(this); BadgeStore.connection(true); refresh()
        seen.clear(); seen.addAll(runCatching { activeNotifications.orEmpty().map { it.key } }.getOrDefault(emptyList()))
    }
    override fun onListenerDisconnected() {
        if(BadgeStore.listener.get() === this) {
            BadgeStore.listener.clear()
            BadgeStore.connection(false)
        }
        removedKeys.clear()
        super.onListenerDisconnected()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn?.let { removedKeys.remove(it.key) }
        if(sbn != null && seen.add(sbn.key) && sbn.user == Process.myUserHandle() && !sbn.isOngoing &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) BadgeStore.newNotification()
        refresh()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn?.let {
            seen.remove(it.key)
            removedKeys.add(it.key)
        }
        refresh()
    }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) { refresh() }
    internal fun refresh() {
        if(BadgeStore.listener.get() !== this || !BadgeStore.connected.value) return
        val marks = runCatching {
            val rankings = currentRanking
            val active = activeNotifications.orEmpty()
            // A listener callback can arrive before the active list reflects the removal.
            if(removedKeys.isNotEmpty()) removedKeys.retainAll(active.mapTo(HashSet()) { it.key })
            active.asSequence().filterNot { it.key in removedKeys }.take(2000).map { sbn ->
                val ranking = Ranking()
                val allowed = rankings?.getRanking(sbn.key, ranking) != true || ranking.canShowBadge()
                NotificationMark(sbn.key, sbn.packageName, sbn.user.hashCode(), sbn.groupKey.orEmpty(),
                    sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                    sbn.isOngoing, allowed, sbn.notification.number,
                    isMessageNotification(sbn.notification.category, sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE)))
            }.toList()
        }.getOrDefault(emptyList())
        val user = Process.myUserHandle().hashCode()
        // Pet indicators remain independent of the optional icon badges/channel badge setting.
        BadgeStore.messages(petNotificationSources(marks, user))
        BadgeStore.publish(if(enabled) countBadges(marks, user) else emptyMap())
    }
    override fun onDestroy() {
        scope.cancel()
        if(BadgeStore.listener.get() === this) { BadgeStore.listener.clear(); BadgeStore.connection(false) }
        super.onDestroy()
    }
}
