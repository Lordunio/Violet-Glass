package pl.violetglass.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.violetglass.launcher.apps.AppsRepository
import pl.violetglass.launcher.apps.LauncherApp
import pl.violetglass.launcher.icons.decodeIconChoices
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import pl.violetglass.launcher.R

@Composable
fun AppIcon(app: LauncherApp?, title: String, repository: AppsRepository, revision: Long, size: Dp = 48.dp, showBadge: Boolean = true, modifier: Modifier = Modifier) {
    val prefs = LocalLauncherPreferences.current
    val choices = remember(prefs.iconOverrides) { decodeIconChoices(prefs.iconOverrides) }
    val appearance = prefs.iconPack to choices[app?.key]
    var icon by remember(app?.key, appearance) { mutableStateOf(app?.let { repository.previewIcon(it, appearance.first, appearance.second) }) }
    LaunchedEffect(app?.key, revision, appearance) {
        app?.let { repository.icon(it, appearance.first, appearance.second) }?.let { icon = it }
    }
    val bitmap = icon
    WithNotificationBadge(if(showBadge) LocalBadgeCounts.current[app?.component?.packageName] ?: 0 else 0, Modifier.size(size).then(modifier)) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_app_placeholder), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f), modifier = Modifier.size(size * .65f))
    }
    }
}
