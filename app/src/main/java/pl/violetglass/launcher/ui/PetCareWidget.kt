package pl.violetglass.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import pl.violetglass.launcher.R
import pl.violetglass.launcher.pet.*
import pl.violetglass.launcher.settings.GlassTarget
import pl.violetglass.launcher.settings.ThemeMode

enum class PetWidgetKind(val key: String, @param:StringRes val title: Int, val columns: Int, val rows: Int,
    val minimumColumns: Int, private val heightDp: Int) {
    QUICK("quick", R.string.pet_widget_quick, 2, 1, 2, 56),
    CARD("card", R.string.pet_widget_card, 3, 2, 2, 118),
    ROOM("room", R.string.pet_widget_room, 4, 2, 3, 112);

    val provider: String get() = "pl.violetglass.launcher/.builtin.pet.$key"

    fun preferredHeight(spanY: Int) = when {
        this != QUICK && spanY == 1 -> 74.dp
        else -> (heightDp + (spanY - rows) * 30).dp
    }

    companion object {
        fun fromProvider(provider: String?) = entries.firstOrNull { it.provider == provider }
    }
}

@Composable
internal fun PetCareWidget(kind: PetWidgetKind, model: PetViewModel?, itemId: String = "") {
    val prefs = LocalLauncherPreferences.current
    VioletTheme(ThemeMode.DARK) {
        if (model == null) {
            // The catalog has a solid dialog background, so show the palette itself.
            CompositionLocalProvider(LocalLauncherPreferences provides prefs.copy(glass = prefs.glass.copy(transparency = 0)),
                LocalGlassBackdrop provides null) {
                PetCareContent(kind, model, itemId)
            }
        } else PetCareContent(kind, model, itemId)
    }
}

@Composable
private fun PetCareContent(kind: PetWidgetKind, model: PetViewModel?, itemId: String) {
    val frame = model?.ui?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(PetUiState(ready = true)) }
    val settings = model?.settings?.collectAsStateWithLifecycle()?.value ?: PetSettings(appearance = PetAppearance.PIXEL_FOX)
    val care = model?.careEvents?.collectAsStateWithLifecycle()?.value
    val progress = rememberPetCareProgress(care)
    val fullness by remember(frame) { derivedStateOf { frame.value.stats.fullness.roundToInt() } }
    val energy by remember(frame) { derivedStateOf { frame.value.stats.energy.roundToInt() } }
    val cleanliness by remember(frame) { derivedStateOf { frame.value.stats.cleanliness.roundToInt() } }
    val ready by remember(frame, model) { derivedStateOf { model != null && frame.value.ready } }
    val robot = settings.appearance == PetAppearance.HOME_ROBOT
    val restHere = settings.restPlace == petRestItem(itemId)
    val room = kind == PetWidgetKind.ROOM
    val forest = remember(robot) { PixelWallpaper(if (robot) PetBackgroundMode.NEON_CITY else PetBackgroundMode.PIXEL_FOREST) }

    val glass = LocalLauncherPreferences.current.glass
    val translucent = LocalGlassPolicy.current.enabled && GlassTarget.PET_WIDGETS !in glass.disabledTargets
    val transparency = if (translucent) glass.transparency.coerceIn(0, 100) / 100f else 0f
    GlassSurface(GlassTarget.PET_WIDGETS, Modifier.fillMaxSize().testTag("petCare:${kind.key}")) {
        val ink = if (room || transparency >= .40f) Color.White else LocalContentColor.current
        val shadow = if (ink == Color.White && (room || transparency >= .40f)) Shadow(Color.Black, blurRadius = 5f) else null
        ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = ink, shadow = shadow)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxHeight < 90.dp
                if (room) {
                    Canvas(Modifier.matchParentSize().graphicsLayer { alpha = 1f - transparency * .65f }) {
                        forest.draw(this, Offset.Zero)
                        drawRect(Color(0xFF221A38).copy(alpha = .28f))
                        val unit = 2.dp.toPx()
                        val lamp = Offset(size.width * .75f, size.height * .16f)
                        drawRect(Color(0xFF65524C), Offset(lamp.x + unit * 2f, 0f), Size(unit, lamp.y))
                        drawCircle(Color(0xFFFADF91).copy(alpha = .12f), unit * 6f, lamp + Offset(unit * 2f, unit * 3f))
                        drawRect(Color(0xFF775843), lamp, Size(unit * 5, unit * 6))
                        drawRect(Color(0xFFFFD67F), lamp + Offset(unit, unit), Size(unit * 3, unit * 4))
                        drawRect(Color(0xFF171B26).copy(alpha = .56f), Offset(0f, size.height * .65f), Size(size.width, size.height * .35f))
                        for (index in 0..6) {
                            val x = size.width * (.05f + .15f * index)
                            val y = size.height * (if (index % 2 == 0) .88f else .94f)
                            drawRect(Color(0xFF64886A).copy(alpha = .55f), Offset(x, y), Size(unit * 2f, unit))
                        }
                    }
                }

                val feed = { model?.care(PetCareAction.FEED, itemId); Unit }
                val clean = { model?.care(PetCareAction.CLEAN, itemId); Unit }
                when (kind) {
                    PetWidgetKind.QUICK -> Row(Modifier.fillMaxSize().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        PetCarePortrait(frame, settings.appearance, care, progress, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(Modifier.fillMaxHeight(.64f), color = Color.White.copy(alpha = .20f))
                        CareAction(PetCareAction.FEED, R.string.pet_widget_feed, robot, ready, fullness < 30, onClick = feed)
                        VerticalDivider(Modifier.fillMaxHeight(.64f), color = Color.White.copy(alpha = .20f))
                        CareAction(PetCareAction.CLEAN, R.string.pet_widget_clean, robot, ready, cleanliness < 30, onClick = clean)
                    }
                    PetWidgetKind.CARD -> Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PetCarePortrait(frame, settings.appearance, care, progress, Modifier.weight(.36f).fillMaxHeight())
                            Column(Modifier.weight(.64f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                                PetMeter(R.string.pet_widget_fullness, fullness, "fullness", compact)
                                PetMeter(R.string.pet_widget_energy, energy, "energy", compact)
                                PetMeter(R.string.pet_widget_cleanliness, cleanliness, "cleanliness", compact)
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .18f))
                        Row(Modifier.height(if (compact) 26.dp else 42.dp).fillMaxWidth()) {
                            val labels = !compact && this@BoxWithConstraints.maxWidth >= 156.dp
                            CareAction(PetCareAction.FEED, R.string.pet_widget_feed, robot, ready, fullness < 30, showLabel = labels, onClick = feed)
                            CareAction(PetCareAction.CLEAN, R.string.pet_widget_clean, robot, ready, cleanliness < 30, showLabel = labels, onClick = clean)
                            CareAction(PetCareAction.PAT, R.string.pet_widget_pat, robot, ready, showLabel = labels) { model?.care(PetCareAction.PAT, itemId) }
                        }
                    }
                    PetWidgetKind.ROOM -> Row(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.Bottom) {
                        PetCarePortrait(frame, settings.appearance, care, progress, Modifier.weight(1.2f).fillMaxHeight(),
                            restingInRoom = restHere, onGround = true)
                        CareAction(PetCareAction.FEED, R.string.pet_widget_feed, robot, ready, fullness < 30, scenery = true, onClick = feed)
                        CareAction(PetCareAction.CLEAN, R.string.pet_widget_clean, robot, ready, cleanliness < 30, scenery = true, onClick = clean)
                        CareAction(PetCareAction.PLAY, R.string.pet_widget_play, robot, ready, scenery = true) { model?.care(PetCareAction.PLAY, itemId) }
                        CareAction(PetCareAction.REST, if (restHere) R.string.pet_widget_rest_set else R.string.pet_widget_rest,
                            robot, ready, selected = restHere, scenery = true) { model?.care(PetCareAction.REST, itemId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PetCarePortrait(frame: State<PetUiState>, appearance: PetAppearance, care: PetCareEvent?, progress: State<Float>,
    modifier: Modifier, restingInRoom: Boolean = false, onGround: Boolean = false) {
    Canvas(modifier) {
        val state = frame.value
        val spriteSize = minOf(size.width * .82f, size.height * .70f, 54.dp.toPx())
        if (spriteSize >= 16f) {
            val mode = if (state.mode == PetMode.SLEEPING && !restingInRoom) PetMode.IDLE else state.mode
            val y = if (onGround) size.height - 15.dp.toPx() else center.y + spriteSize * .42f
            drawPetCareCharacter(state.copy(visible = true, appearance = appearance, facing = 1, mode = mode,
                sizePx = spriteSize, feet = Offset(center.x, y)), care, progress.value, showEnvelope = false)
        }
    }
}

@Composable
private fun PetMeter(@StringRes title: Int, value: Int, key: String, compact: Boolean) {
    val name = stringResource(title)
    val meter = Modifier.testTag("petMeter:$key").semantics { contentDescription = "$name $value%" }
    Row(meter.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, Modifier.weight(1.60f), fontSize = 9.sp,
            lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$value%", fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
        PetProgressTrack(value / 100f, Modifier.weight(1f).height(if (compact) 3.dp else 5.dp))
    }
}

@Composable
internal fun PetProgressTrack(value: Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val track = ink.copy(alpha = .22f)
    Canvas(modifier) {
        val radius = size.height / 2f
        drawRoundRect(track, cornerRadius = CornerRadius(radius))
        val width = size.width * value.coerceIn(0f, 1f)
        if (width > 0f) drawRoundRect(ink, size = Size(width, size.height),
            cornerRadius = CornerRadius(minOf(radius, width / 2f)))
    }
}
@Composable
private fun RowScope.CareAction(action: PetCareAction, @StringRes title: Int, robot: Boolean, enabled: Boolean,
    needsCare: Boolean = false, selected: Boolean = false, showLabel: Boolean = false, scenery: Boolean = false, onClick: () -> Unit) {
    val label = stringResource(title)
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.weight(1f).heightIn(max = if (scenery) 46.dp else 48.dp).fillMaxHeight().padding(1.dp)
        .then(if (selected) Modifier.background(Color(0xFF544670).copy(alpha = .68f), shape) else Modifier)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(if (scenery) 28.dp else 22.dp)) {
                drawPetCareIcon(action, Offset.Zero, size.minDimension, robot)
            }
            if (showLabel) Text(label, fontSize = 9.sp,
                lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (needsCare) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(Color(0xFFFFC65F), CircleShape))
    }
}
