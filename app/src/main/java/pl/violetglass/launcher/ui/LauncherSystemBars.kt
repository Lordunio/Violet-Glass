package pl.violetglass.launcher.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import pl.violetglass.launcher.MainActivity

/** Policy follows the visible background, independent of pet animation, drag and modal state. */
@Composable
internal fun LauncherSystemBars(cyberBackground: Boolean, photoBackground: Boolean) {
    val activity = LocalActivity.current as? MainActivity ?: return
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val lightIcons = darkTheme || cyberBackground || photoBackground
    val configuration = LocalConfiguration.current
    LaunchedEffect(activity, lightIcons, configuration.orientation, configuration.uiMode) {
        activity.setSystemBarIcons(lightIcons)
    }
    if(photoBackground && !cyberBackground) {
        // Wallpaper pixels are protected; a scrim keeps white icons readable even on a white photo.
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxWidth().height(top + 8.dp).testTag("statusBarContrast")) {
            val barHeight = with(density) { top.toPx() }
            drawRect(Color.Black.copy(alpha = .6f), size = Size(size.width, barHeight))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent),
                startY = barHeight, endY = size.height), topLeft = Offset(0f, barHeight), size = Size(size.width, size.height - barHeight))
        }
    }
}
