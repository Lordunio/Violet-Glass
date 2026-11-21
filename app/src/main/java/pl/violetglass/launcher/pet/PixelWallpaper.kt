package pl.violetglass.launcher.pet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The three low-resolution layers are painted once per viewport, then moved independently. */
internal class PixelWallpaper(private val mode: PetBackgroundMode) {
    private var resolution = IntSize.Zero
    private var layers = emptyList<ImageBitmap>()

    fun draw(scope: DrawScope, tilt: Offset) = with(scope) {
        val pixel = maxOf(size.minDimension / 100f, size.maxDimension / 280f).roundToInt().coerceAtLeast(1)
        val width = ceil(size.width / pixel).toInt().coerceAtLeast(1)
        val height = ceil(size.height / pixel).toInt().coerceAtLeast(1)
        if (resolution != IntSize(width, height)) {
            resolution = IntSize(width, height)
            layers = when (mode) {
                PetBackgroundMode.PIXEL_FOREST -> forest(width, height)
                PetBackgroundMode.NEON_CITY -> city(width, height)
                else -> error("Unsupported pixel wallpaper: $mode")
            }
        }
        layers.forEachIndexed { index, image ->
            val shift = index * .45f
            val margin = OVERSCAN * pixel
            val dx = (tilt.x * shift).roundToInt().coerceIn(-margin, margin)
            val dy = (tilt.y * shift * .45f).roundToInt().coerceIn(-margin, margin)
            drawImage(image, dstOffset = IntOffset(dx - margin, dy - margin),
                dstSize = IntSize(image.width * pixel, image.height * pixel), filterQuality = FilterQuality.None)
        }
    }
}

private const val OVERSCAN = 5
private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

private class Pixels(val width: Int, val height: Int) {
    private val bitmap = Bitmap.createBitmap(width + OVERSCAN * 2, height + OVERSCAN * 2, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap).apply { translate(OVERSCAN.toFloat(), OVERSCAN.toFloat()) }
    private val ink = Paint()

    fun rect(x: Int, y: Int, w: Int, h: Int, rgb: Int) {
        ink.color = opaque(rgb)
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), ink)
    }

    fun sky(top: Int, bottom: Int) {
        ink.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), opaque(top), opaque(bottom), Shader.TileMode.CLAMP)
        canvas.drawRect(-OVERSCAN.toFloat(), -OVERSCAN.toFloat(), (width + OVERSCAN).toFloat(), (height + OVERSCAN).toFloat(), ink)
        ink.shader = null
    }

    fun disc(x: Int, y: Int, radius: Int, rgb: Int) {
        for (dy in -radius..radius) {
            val span = sqrt((radius * radius - dy * dy).toFloat()).toInt()
            rect(x - span, y + dy, 2 * span + 1, 1, rgb)
        }
    }

    fun layer() = bitmap.asImageBitmap()
}

private fun Pixels.stars(random: Random, color: Int) {
    repeat((width * height / 400).coerceAtLeast(8)) {
        val x = random.nextInt(width)
        val y = random.nextInt((height * .54f).roundToInt().coerceAtLeast(1))
        rect(x, y, 1, 1, color)
    }
}

private fun forest(width: Int, height: Int): List<ImageBitmap> {
    val random = Random(187)
    val sky = Pixels(width, height).apply {
        sky(0x091321, 0x285B53)
        stars(random, 0x548680)
        val x = (width * .68f).toInt()
        val y = (height * .24f).toInt()
        val radius = (minOf(width, height) * .085f).toInt().coerceAtLeast(2)
        disc(x, y, radius + 4, 0x1C3C45)
        disc(x, y, radius + 2, 0x285451)
        disc(x, y, radius, 0xB2D1AF)
        rect(x - radius / 2, y - radius / 3, radius / 2 + 1, 2, 0x89B3A1)
        rect(x + 1, y + radius / 3, radius / 2 + 1, 1, 0x8BB8AA)
        for (left in -OVERSCAN..width + OVERSCAN step 2) {
            val ridge = (height * .54f + sin(left * .075f) * 6f + sin(left * .21f) * 2f).toInt()
            rect(left, ridge, 2, height - ridge + OVERSCAN, 0x224C49)
        }
    }
    val woods = Pixels(width, height).apply {
        for (x in -OVERSCAN..width + OVERSCAN step 5) {
            val base = (height * .72f + sin(x * .09f) * 4f).toInt()
            pine(x, base, random.nextInt(14, 31), 0x1A4642, 0x204F48)
        }
        for (x in -OVERSCAN..width + OVERSCAN step 9) {
            val base = (height * .82f + sin(x * .11f) * 5f).toInt()
            pine(x, base, random.nextInt(21, 43), 0x143631, 0x19413A)
        }
        val ground = (height * .77f).toInt()
        rect(-OVERSCAN, ground, width + OVERSCAN * 2, height - ground + OVERSCAN, 0x112E2C)
    }
    val glade = Pixels(width, height).apply {
        val floor = (height * .80f).toInt()
        for (y in floor until height + OVERSCAN step 2) {
            val depth = (y - floor).toFloat() / (height - floor).coerceAtLeast(1)
            val halfWidth = (2 + depth * width * .22f).toInt()
            val center = (.54f * width + sin(depth * 4.2f) * width * .13f).toInt()
            rect(center - halfWidth, y, halfWidth * 2, 2, 0x294839)
            rect(center - halfWidth, y, 2, 2, 0x355744)
        }
        for (x in -2..width + 2 step 12) {
            if (x in (width * .28f).toInt()..(width * .82f).toInt()) continue
            pine(x, (height * .85f).toInt(), random.nextInt(32, 54), 0x0B2426, 0x113332)
        }
        repeat(width / 2) {
            val x = random.nextInt(width)
            val y = random.nextInt(floor, height.coerceAtLeast(floor + 1))
            rect(x, y, 2, 1, if (it % 4 == 0) 0x7D6352 else 0x365848)
        }
    }
    return listOf(sky.layer(), woods.layer(), glade.layer())
}

private fun Pixels.pine(x: Int, base: Int, size: Int, body: Int, highlight: Int) {
    val top = base - size
    rect(x - 1, base - 6, 3, 7, body)
    for (dy in 0 until size - 3 step 2) {
        val span = (dy * .18f + dy % 8 * .34f).toInt()
        rect(x - span, top + dy, span * 2 + 1, 2, body)
        if (span > 2) rect(x - span, top + dy, 2, 1, highlight)
    }
}

private fun city(width: Int, height: Int): List<ImageBitmap> {
    val random = Random(321)
    val horizon = (height * .70f).toInt()
    val sky = Pixels(width, height).apply {
        sky(0x0C0F23, 0x40346A)
        stars(random, 0x666590)
        val x = (width * .29f).toInt()
        val y = (height * .24f).toInt()
        val radius = (minOf(width, height) * .075f).toInt().coerceAtLeast(2)
        disc(x, y, radius + 3, 0x282548)
        disc(x, y, radius, 0xC7A1CA)
        for (dy in 0..radius step 3) {
            val span = sqrt((radius * radius - dy * dy).toFloat()).toInt()
            rect(x - span, y + dy, span * 2 + 1, 1, 0x795688)
        }
        for (left in -OVERSCAN..width + OVERSCAN step 5) {
            val top = horizon - random.nextInt(13, 37)
            rect(left, top, 4, horizon - top + 1, 0x2A2B55)
            rect(left + 1, top + 3, 1, 1, 0x655D87)
        }
    }
    val skyline = Pixels(width, height).apply {
        val ground = (height * .79f).toInt()
        rect(-OVERSCAN, horizon, width + OVERSCAN * 2, height - horizon + OVERSCAN, 0x191930)
        var x = -OVERSCAN
        while (x < width + OVERSCAN) {
            val span = random.nextInt(7, 14)
            val tower = random.nextInt(16, 41)
            building(x, horizon - tower, span, horizon + 2, 0x1D2242, random)
            x += span + 2
        }
        for (y in horizon until height + OVERSCAN step 2) {
            val step = ((y - horizon).toFloat() / (height - horizon).coerceAtLeast(1) * width * .44f).toInt()
            rect(width / 2 - step, y, step * 2 + 1, 2, 0x292940)
            if (y >= ground && y % 8 == 0) rect(width / 2 - 1, y, 2, 2, 0x5E527D)
        }
    }
    val towers = Pixels(width, height).apply {
        val base = (height * .83f).toInt()
        val span = (width * .21f).toInt().coerceAtLeast(6)
        building(-OVERSCAN, (height * .49f).toInt(), span, base, 0x11192D, random)
        building(width - span, (height * .45f).toInt(), span + OVERSCAN, base - 2, 0x13192D, random)
        building((width * .19f).toInt(), (height * .62f).toInt(), (span / 2).coerceAtLeast(4), base - 8, 0x151B33, random)
        building((width * .72f).toInt(), (height * .58f).toInt(), (span / 2).coerceAtLeast(4), base - 5, 0x171933, random)
        rect(4, (height * .62f).toInt(), 5, 12, 0x5C316B)
        rect(5, (height * .62f).toInt() + 2, 3, 7, 0xC66DAA)
        rect(width - 9, (height * .57f).toInt(), 5, 15, 0x274F67)
        rect(width - 8, (height * .57f).toInt() + 2, 3, 9, 0x6AADBB)
        repeat(width / 3) {
            val y = random.nextInt(base, height.coerceAtLeast(base + 1))
            rect(random.nextInt(width), y, random.nextInt(2, 7), 1, if (it % 2 == 0) 0x503452 else 0x284B63)
        }
    }
    return listOf(sky.layer(), skyline.layer(), towers.layer())
}

private fun Pixels.building(x: Int, top: Int, w: Int, base: Int, color: Int, random: Random) {
    rect(x, top, w, base - top, color)
    rect(x + 1, top - 2, (w - 2).coerceAtLeast(1), 2, color)
    for (row in top + 4 until base - 2 step 5) {
        for (column in x + 2 until x + w - 1 step 3) {
            if (random.nextInt(4) == 0) continue
            val glow = when (random.nextInt(5)) {
                0 -> 0xA16789
                1 -> 0x619899
                2 -> 0x9D7B60
                else -> 0x3A5072
            }
            rect(column, row, 1, 2, glow)
        }
    }
}
