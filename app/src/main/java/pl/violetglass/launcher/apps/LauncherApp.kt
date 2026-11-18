package pl.violetglass.launcher.apps

import android.content.ComponentName
import android.os.UserHandle

data class LauncherApp(val component: ComponentName, val user: UserHandle, val label: String) {
    val key: String get() = "${user.hashCode()}:${component.flattenToString()}"
}

