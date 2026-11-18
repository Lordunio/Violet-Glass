package pl.violetglass.launcher.gestures

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.widget.Toast
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.*

class LauncherDeviceAdmin : DeviceAdminReceiver()

class SystemActions(private val activity: Activity) {
    fun uninstall(app: pl.violetglass.launcher.apps.LauncherApp) = safely {
        check(app.user == Process.myUserHandle())
        val name = app.component.packageName
        pl.violetglass.launcher.apps.UninstallJournal.begin(activity, name)
        try { activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", name, null))) }
        catch (error: RuntimeException) { pl.violetglass.launcher.apps.UninstallJournal.clear(activity, name); throw error }
    }
    private val admin = ComponentName(activity, LauncherDeviceAdmin::class.java)
    private val policy = activity.getSystemService(DevicePolicyManager::class.java)
    fun isLockEnabled() = runCatching { policy.isAdminActive(admin) }.getOrDefault(false)
    fun enableLock() = safely {
        activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, activity.getString(R.string.lock_explanation)))
    }
    fun disableLock() = safely { policy.removeActiveAdmin(admin) }
    fun execute(binding: GestureBinding) = safely {
        val component = ComponentName.unflattenFromString(binding.appKey.substringAfter(':'))
        val launcher = activity.getSystemService(LauncherApps::class.java)
        when (binding.action) {
            GestureAction.APP -> {
                requireNotNull(component)
                launcher.startMainActivity(component, Process.myUserHandle(), null, null)
            }
            GestureAction.SHORTCUT -> {
                requireNotNull(component)
                check(launcher.hasShortcutHostPermission())
                launcher.startShortcut(component.packageName, binding.shortcutId, null, null, Process.myUserHandle())
            }
            GestureAction.WALLPAPER -> {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), activity.getString(R.string.wallpaper)))
            }
            GestureAction.LOCK -> {
                if (policy.isAdminActive(admin)) policy.lockNow()
                else Toast.makeText(activity, R.string.lock_needs_permission, Toast.LENGTH_LONG).show()
            }
            else -> Unit
        }
    }
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) { Toast.makeText(activity, R.string.gesture_action_error, Toast.LENGTH_LONG).show() }
    }
}

