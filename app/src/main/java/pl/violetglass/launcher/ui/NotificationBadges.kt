package pl.violetglass.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.violetglass.launcher.R
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.notifications.*
import pl.violetglass.launcher.settings.BadgeStyle

val LocalBadgeCounts = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

@Composable
fun WithNotificationBadge(count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val prefs = LocalLauncherPreferences.current
    Box(modifier) {
        content()
        if(prefs.badgesEnabled && count > 0) {
            val description = stringResource(R.string.badge_count, count)
            val badge = Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).testTag("notificationBadge")
                .semantics { contentDescription = description }
            if(prefs.badgeStyle == BadgeStyle.DOT) Badge(modifier = badge.size(9.dp))
            else Badge(modifier = badge) { Text(if(count > 99) "99+" else count.toString()) }
        }
    }
}

@Composable
fun BadgeSettings(model: LauncherViewModel) {
    val prefs = LocalLauncherPreferences.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var access by remember { mutableStateOf(BadgeStore.hasAccess(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) {
            access = BadgeStore.hasAccess(context); BadgeStore.refreshAccess(context)
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    Text(stringResource(R.string.notification_badges), style = MaterialTheme.typography.titleMedium)
    Toggle(stringResource(R.string.show_badges), prefs.badgesEnabled, "badgesEnabled") { model.updatePreferences { p -> p.copy(badgesEnabled = it) } }
    if(prefs.badgesEnabled) {
        BadgeStyle.entries.forEach { style -> RadioRow(stringResource(if(style == BadgeStyle.DOT) R.string.badge_dot else R.string.badge_number),
            prefs.badgeStyle == style, "badgeStyle:${style.name}") { model.updatePreferences { it.copy(badgeStyle = style) } } }
        Text(stringResource(if(access) R.string.badge_access_granted else R.string.badge_access_needed), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val component = ComponentName(context, BadgeListener::class.java).flattenToString()
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        }, modifier = Modifier.testTag("badgeAccess")) { Text(stringResource(R.string.badge_access_settings)) }
    }
}
