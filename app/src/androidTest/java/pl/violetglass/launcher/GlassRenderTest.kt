package pl.violetglass.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ui.*

/** Verifies pixels of real GPU blur, rather than only checking a settings label. */
@RunWith(AndroidJUnit4::class)
class GlassRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun backdropReallyBlursAndUpdatesWhileForegroundRemainsSharp() {
        var blur by mutableIntStateOf(0)
        var inverted by mutableStateOf(false)
        compose.setContent {
            val settings = GlassSettings(mode = GlassMode.ULTRA, automatic = false, blur = blur, transparency = 100, reflections = 0, animation = 0, corners = 0)
            val source = rememberGraphicsLayer().let { remember(it) { GlassBackdrop(it) } }
            CompositionLocalProvider(LocalLauncherPreferences provides LauncherPreferences(glass = settings),
                LocalGlassPolicy provides resolveGlass(settings, GlassEnvironment()), LocalGlassBackdrop provides source) {
                VioletTheme {
                    Box(Modifier.size(200.dp).testTag("renderFixture")) {
                        Canvas(Modifier.matchParentSize().recordGlassBackdrop(source)) {
                            val stripe = 8.dp.toPx()
                            for (i in 0..(size.width / stripe).toInt()) drawRect(
                                if ((i % 2 == 0) xor inverted) Color.White else Color.Black,
                                topLeft = Offset(i * stripe, 0f), size = Size(stripe, size.height))
                        }
                        GlassSurface(GlassTarget.PANELS, Modifier.matchParentSize()) {
                            Box(Modifier.offset(80.dp, 80.dp).size(40.dp).background(Color.Magenta))
                        }
                    }
                }
            }
        }
        fun pixels() = compose.onNodeWithTag("renderFixture").captureToImage().toPixelMap()
        fun variance(p: PixelMap): Float {
            val values = (20 until p.width - 20).map { p[it, p.height / 4].red }
            val mean = values.average().toFloat()
            return values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size
        }
        val sharp = pixels()
        compose.runOnIdle { blur = 100 }
        val blurred = pixels()
        assertTrue("Background stripes must actually blur", variance(blurred) < variance(sharp) * .35f)
        val center = blurred[blurred.width / 2, blurred.height / 2]
        assertTrue("Foreground magenta remains sharp", center.red > .95f && center.blue > .95f && center.green < .05f)
        val before = blurred[blurred.width / 8, blurred.height / 4].red
        compose.runOnIdle { inverted = true; blur = 15 }
        val changed = pixels()
        assertTrue("Live backdrop must refresh", kotlin.math.abs(changed[changed.width / 8, changed.height / 4].red - before) > .03f)
    }
}
