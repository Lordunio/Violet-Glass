package pl.violetglass.launcher.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import pl.violetglass.launcher.icons.*

/** Only the current Android user; managed/private profiles need a separate UX and testing. */
class AppsRepository(private val context: Context) {
    private val removed = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val removedPackages = removed.receiveAsFlow()
    fun canUninstall(app: LauncherApp): Boolean = app.user == Process.myUserHandle() && app.component.packageName != context.packageName && runCatching {
        context.packageManager.getApplicationInfo(app.component.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)
    val iconPacks = IconPacks(context)
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val iconLock = Mutex()
    private val previews = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private fun appearanceKey(app: LauncherApp, pack: String, choice: IconChoice?) = "${app.key}|${choice?.pack ?: pack}|${choice?.drawable.orEmpty()}"
    fun previewIcon(app: LauncherApp, pack: String = "", choice: IconChoice? = null): Bitmap? = previews[appearanceKey(app, pack, choice)]
    suspend fun warmIcons(apps: List<LauncherApp>, pack: String, choices: Map<String, IconChoice>) {
        for(app in apps) { icon(app, pack, choices[app.key]); yield() }
    }
    private var signatures = emptyMap<String, String>()
    private val generation = AtomicLong()
    val iconRevision: Long get() = generation.get()
    internal val cachedIconBytes: Int get() = icons.size()

    @Suppress("DEPRECATION") // These signals also cover our minimum Android 12.
    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) synchronized(icons) {
            generation.incrementAndGet(); icons.evictAll(); previews.evictAll()
        }
    }

    val changes = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            private fun changed() { synchronized(icons) { generation.incrementAndGet(); icons.evictAll(); previews.evictAll() }; trySend(Unit) }
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                if (user == Process.myUserHandle()) removed.trySend(packageName)
                changed()
            }
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        }
        val registered = try {
            launcher.registerCallback(callback)
            true
        } catch (_: SecurityException) {
            // Some OEMs restrict callbacks; onResume and retry still refresh the list.
            false
        }
        trySend(Unit)
        awaitClose { if (registered) launcher.unregisterCallback(callback) }
    }.conflate()

    suspend fun load(): List<LauncherApp> = withContext(Dispatchers.IO) {
        // APK path/mtime and density also detect updates missed while callbacks were stopped.
        val activities = launcher.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != context.packageName }
            .distinctBy { it.componentName }
        val apps = activities.map { LauncherApp(it.componentName, it.user,
            runCatching { it.label.toString() }.getOrDefault(it.componentName.shortClassName)) }
        val next = activities.zip(apps).associate { (info, app) ->
            val apk = info.applicationInfo.sourceDir.orEmpty()
            app.key to "$apk:${File(apk).lastModified()}:${context.resources.configuration}: ${app.label}"
        } + iconPacks.installed().associate { "pack:${it.packageName}" to iconPacks.stamp(it.packageName) }
        synchronized(icons) {
            if (next != signatures) {
                icons.evictAll()
                previews.evictAll()
                signatures = next
                generation.incrementAndGet()
            }
        }
        AppSearch.sort(apps)
    }

    suspend fun icon(app: LauncherApp, pack: String = "", choice: IconChoice? = null): Bitmap? {
        return withContext(Dispatchers.IO) { iconLock.withLock {
        val selectedPack = choice?.pack ?: pack
        val key = "${app.key}|${iconPacks.stamp(selectedPack)}|${choice?.drawable.orEmpty()}"
        icons[key] ?: try {
            val revision = generation.get()
            val drawable = choice?.drawable ?: iconPacks.index(selectedPack).components[app.component]
            val themed = drawable?.let { iconPacks.bitmap(selectedPack, it) }
            val info = launcher.getActivityList(app.component.packageName, app.user)
                .firstOrNull { it.componentName == app.component } ?: return@withLock null
            (themed ?: info.getBadgedIcon(context.resources.displayMetrics.densityDpi)
                .toBitmap(144, 144)).also { bitmap -> synchronized(icons) {
                    if (revision == generation.get()) {
                        icons.put(key, bitmap)
                        previews.put(appearanceKey(app, pack, choice), Bitmap.createScaledBitmap(bitmap, 48, 48, true))
                    }
                } }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { null }
        } }
    }

    suspend fun shortcuts(app: LauncherApp): List<android.content.pm.ShortcutInfo> = withContext(Dispatchers.IO) {
        check(launcher.hasShortcutHostPermission())
        launcher.getShortcuts(LauncherApps.ShortcutQuery().setPackage(app.component.packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), app.user)
            .orEmpty().filter { it.isEnabled }.sortedBy { it.shortLabel.toString() }
    }
    fun launch(app: LauncherApp): Boolean = try {
        launcher.startMainActivity(app.component, app.user, null, null)
        true
    } catch (_: SecurityException) { false
    } catch (_: android.content.ActivityNotFoundException) { false
    } catch (_: IllegalStateException) { false }
}


