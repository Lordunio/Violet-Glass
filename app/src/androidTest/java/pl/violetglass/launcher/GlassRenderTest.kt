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

    @Test fun appNameHasVisibleGlyphsOnBlackWhiteAndViolet() {
        val backgrounds = listOf(Color.Black, Color.White, Color(0xFF675577))
        compose.setContent {
            Column {
                backgrounds.forEachIndexed { index, background ->
                    Box(Modifier.background(background).testTag("backdrop$index")) {
                        DrawerAppLabel("Galeria", Modifier.padding(4.dp),
                            androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            outlined = true, maxLines = 2, singleWordSize = null)
                    }
                }
            }
        }
        backgrounds.indices.forEach { index ->
            val pixels = compose.onNodeWithTag("backdrop$index").captureToImage().toPixelMap()
            var edges = 0
            for (y in 0 until pixels.height) for (x in 1 until pixels.width) {
                val left = pixels[x - 1, y].luminance()
                val right = pixels[x, y].luminance()
                if ((maxOf(left, right) + .05f) / (minOf(left, right) + .05f) >= 4.5f) edges++
            }
            assertTrue("Missing contrasting letter edges on background $index ($edges)", edges >= 10)
            assertEquals(backgrounds[index], pixels[0, 0])
        }
    }

    @Test fun searchHintKeepsLightAndDarkLetterEdgesAtHighTransparency() {
        var transparency by mutableIntStateOf(75)
        compose.setContent {
            val glass = GlassSettings(mode = GlassMode.LITE, transparency = transparency)
            CompositionLocalProvider(LocalLauncherPreferences provides LauncherPreferences(glass = glass),
                LocalGlassPolicy provides resolveGlass(glass, GlassEnvironment())) {
                VioletTheme(ThemeMode.LIGHT) {
                    Column {
                        listOf(Color.Black, Color.White, Color(0xFF675577)).forEachIndexed { index, background ->
                            Box(Modifier.background(background)) {
                                GlassSurface(GlassTarget.SEARCH) {
                                    DrawerSearchHint(Modifier.padding(10.dp).testTag("hint$index"))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (level in listOf(75, 100)) {
            compose.runOnIdle { transparency = level }
            repeat(3) { index ->
                val pixels = compose.onNodeWithTag("hint$index").captureToImage().toPixelMap()
                var edges = 0
                for (y in 0 until pixels.height) for (x in 1 until pixels.width) {
                    val a = pixels[x - 1, y].luminance()
                    val b = pixels[x, y].luminance()
                    if ((maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f) edges++
                }
                assertTrue("Search hint on background $index at $level%: $edges edges", edges >= 20)
            }
        }
    }

    @Test fun drawerAndSearchUseTheFullTransparencyRange() {
        var transparency by mutableIntStateOf(0)
        compose.setContent {
            val glass = GlassSettings(mode = GlassMode.LITE, transparency = transparency)
            CompositionLocalProvider(LocalLauncherPreferences provides LauncherPreferences(glass = glass),
                LocalGlassPolicy provides resolveGlass(glass, GlassEnvironment())) {
                VioletTheme(ThemeMode.LIGHT) {
                    Box(Modifier.size(120.dp).background(Color.Black).testTag("drawerFixture")) {
                        Row(Modifier.matchParentSize()) {
                            GlassSurface(GlassTarget.DRAWER, Modifier.weight(1f).fillMaxHeight()) {}
                            GlassSurface(GlassTarget.SEARCH, Modifier.weight(1f).fillMaxHeight()) {}
                        }
                    }
                }
            }
        }
        fun samples(): List<Color> = compose.onNodeWithTag("drawerFixture").captureToImage().toPixelMap().let { pixels ->
            listOf(pixels[pixels.width / 4, pixels.height / 2], pixels[pixels.width * 3 / 4, pixels.height / 2])
        }
        val opaque = samples()
        assertTrue(opaque.all { it.blue > it.red && it.red > it.green })
        for (step in listOf(25, 50, 75, 100)) {
            compose.runOnIdle { transparency = step }
            samples().zip(opaque).forEach { (color, start) ->
                val ratio = 1f - step / 100f
                assertEquals("Blue channel at $step% transparency", start.blue * ratio, color.blue, .02f)
            }
        }
    }

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
