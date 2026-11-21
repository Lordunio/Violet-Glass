package pl.violetglass.launcher.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import pl.violetglass.launcher.R
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal class PetGame(private val random: Random = Random.Default) {
    val id = UUID.randomUUID().toString()
    enum class Bonus { RAPID, DOUBLE, SHIELD }
    data class Shot(val x: Float, var y: Float, val boosted: Boolean)
    data class Drop(val kind: Bonus, val x: Float, var y: Float)
    data class Burst(val x: Float, val y: Float, val started: Float, val bonus: Bonus? = null)
    data class Fly(val lane: Float, val phase: Float, val speed: Float, var y: Float = -.04f) {
        fun x(time: Float) = lane + sin(time * 2.4f + phase) * .065f
    }

    val shots = mutableListOf<Shot>()
    val flies = mutableListOf<Fly>()
    val drops = mutableListOf<Drop>()
    val bursts = mutableListOf<Burst>()
    var x = .5f; private set
    var time = 0f; private set
    var score = 0; private set
    var lives = 3; private set
    private var target = x
    private var fireIn = 0f
    private var spawnIn = .35f
    private var rapidUntil = 0f
    private var doubleUntil = 0f
    private var shieldUntil = 0f
    val shield get() = shieldUntil > time
    val shieldFraction get() = ((shieldUntil - time) / BONUS_DURATION).coerceIn(0f, 1f)
    val shieldSeconds get() = secondsLeft(shieldUntil)
    val rapidSeconds get() = secondsLeft(rapidUntil)
    val doubleSeconds get() = secondsLeft(doubleUntil)
    private fun secondsLeft(until: Float) = ceil((until - time).coerceIn(0f, BONUS_DURATION)).toInt()

    fun steer(point: Float) { target = point.coerceIn(.09f, .91f) }

    fun step(elapsed: Float) {
        if (lives == 0 || !elapsed.isFinite() || elapsed <= 0f) return
        val dt = elapsed.coerceAtMost(.05f)
        time += dt
        val distance = target - x
        x = if (abs(distance) < .002f) target
            else x + (distance * dt * 7f).coerceIn(-dt * .78f, dt * .78f)
        fireIn -= dt
        spawnIn -= dt
        if (fireIn <= 0f) {
            val rapid = time < rapidUntil
            val double = time < doubleUntil
            if (double) {
                shots.add(Shot(x - .025f, .86f, true))
                shots.add(Shot(x + .025f, .86f, true))
            } else shots.add(Shot(x, .86f, rapid))
            fireIn += if (rapid) .12f else .29f
        }
        if (spawnIn <= 0f) {
            val pressure = (time / 90f).coerceAtMost(1f)
            val lane = random.nextFloat() * .72f + .14f
            flies.add(Fly(lane, random.nextFloat() * 6.28f, .12f + pressure * .09f + random.nextFloat() * .04f))
            if (time >= 25f && random.nextFloat() < pressure * .75f) {
                val oppositeLane = if (lane < .5f) lane + .36f else lane - .36f
                flies.add(Fly(oppositeLane, random.nextFloat() * 6.28f, .12f + pressure * .09f, y = -.12f))
            }
            spawnIn += .86f - pressure * .46f
        }
        shots.forEach { it.y -= .88f * dt }
        flies.forEach { it.y += it.speed * dt }
        drops.removeAll { drop ->
            drop.y += .45f * dt
            if (abs(drop.x - x) < .09f && drop.y in .85f..0.98f) {
                when (drop.kind) {
                    Bonus.RAPID -> { rapidUntil = time + BONUS_DURATION; fireIn = 0f }
                    Bonus.DOUBLE -> { doubleUntil = time + BONUS_DURATION; fireIn = 0f }
                    Bonus.SHIELD -> shieldUntil = time + BONUS_DURATION
                }
                bursts.add(Burst(drop.x, drop.y, time, drop.kind))
                true
            } else drop.y > 1f
        }
        shots.removeAll { shot ->
            if (shot.y < 0f) true else {
                val hit = flies.indexOfFirst { abs(it.x(time) - shot.x) < .045f && abs(it.y - shot.y) < .032f }
                if (hit == -1) false else {
                    val fly = flies.removeAt(hit)
                    bursts.add(Burst(fly.x(time), fly.y, time))
                    score += 10
                    if (score % 40 == 0) {
                        val kind = Bonus.entries[(score / 40 - 1) % Bonus.entries.size]
                        drops.add(Drop(kind, fly.x(time), fly.y))
                    }
                    true
                }
            }
        }
        flies.removeAll { fly ->
            if (fly.y < .91f) false else {
                if (shield) {
                    shieldUntil = 0f
                    bursts.add(Burst(x, .89f, time, Bonus.SHIELD))
                } else lives = (lives - 1).coerceAtLeast(0)
                true
            }
        }
        bursts.removeAll { time - it.started >= .42f }
    }
}

private const val BONUS_DURATION = 9f

/** Owned by the activity's pet ViewModel, outside the paged game UI. */
internal enum class PetScoreSave { READY, SAVING, SAVED, BELOW_RANKING, FAILED }

internal class PetGameSession {
    var round by mutableStateOf<PetGame?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var scoreSave by mutableStateOf(PetScoreSave.READY)
        private set

    fun start() {
        round = PetGame()
        paused = false
        scoreSave = PetScoreSave.READY
    }

    fun pause() {
        if (round?.lives?.let { it > 0 } == true) paused = true
    }

    fun togglePause() {
        if (paused) paused = false else pause()
    }

    fun step(elapsed: Float) {
        if (!paused) round?.step(elapsed)
    }

    fun beginSaving(): PetGame? {
        val completed = round?.takeIf { it.lives == 0 } ?: return null
        if (scoreSave != PetScoreSave.READY && scoreSave != PetScoreSave.FAILED) return null
        scoreSave = PetScoreSave.SAVING
        return completed
    }

    fun finishSaving(savedRound: PetGame, result: PetScoreSave) {
        if (round === savedRound) scoreSave = result
    }
}

@Composable
internal fun PetGamePanel(model: PetViewModel, appearance: PetAppearance, active: Boolean, locked: Boolean, onToggleLock: () -> Unit) {
    val game = model.gameSession
    val ranking by model.gameScores.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = game.round
    val paused = game.paused
    var score by remember(session) { mutableIntStateOf(session?.score ?: 0) }
    var lives by remember(session) { mutableIntStateOf(session?.lives ?: 3) }
    var rapid by remember(session) { mutableIntStateOf(session?.rapidSeconds ?: 0) }
    var double by remember(session) { mutableIntStateOf(session?.doubleSeconds ?: 0) }
    var shield by remember(session) { mutableIntStateOf(session?.shieldSeconds ?: 0) }
    var frame by remember(session) { mutableFloatStateOf(session?.time ?: 0f) }
    LaunchedEffect(active, paused, session, lifecycle) {
        if (!active || paused || session == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = withFrameNanos { it }
            while (isActive && session.lives > 0) {
                withFrameNanos { now ->
                    game.step((now - previous) / 1e9f)
                    previous = now
                    score = session.score
                    lives = session.lives
                    rapid = session.rapidSeconds
                    double = session.doubleSeconds
                    shield = session.shieldSeconds
                    frame = session.time
                }
            }
        }
    }
    val background = Color(0xFF100D20)
    val light = Color(0xFFF2E9FF)
    val lavender = Color(0xFFCFBCED)
    Column(Modifier.fillMaxSize().background(background).systemBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 4.dp)
        .testTag("petGame"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCounter(GameGlyph.POINTS, score, stringResource(R.string.pet_game_score, score), lavender)
            Spacer(Modifier.width(10.dp))
            GameCounter(GameGlyph.HEART, lives, stringResource(R.string.pet_game_lives, lives), Color(0xFFFFAAF1))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                if (rapid > 0) BonusIcon(PetGame.Bonus.RAPID, stringResource(R.string.pet_game_rapid, rapid), rapid)
                if (double > 0) BonusIcon(PetGame.Bonus.DOUBLE, stringResource(R.string.pet_game_double, double), double)
                if (shield > 0) BonusIcon(PetGame.Bonus.SHIELD, stringResource(R.string.pet_game_shield_time, shield), shield)
            }
            val pauseLabel = stringResource(if (paused) R.string.pet_game_resume else R.string.pet_game_pause)
            IconButton(onClick = game::togglePause, enabled = active && session != null && lives > 0,
                modifier = Modifier.testTag("petGamePause").semantics { contentDescription = pauseLabel }) {
                GameIcon(if (paused) GameGlyph.PLAY else GameGlyph.PAUSE, if (paused) Color(0xFF8AF4EC) else light)
            }
            val resetLabel = stringResource(R.string.pet_game_reset)
            IconButton(onClick = game::start, enabled = active && session != null && lives > 0,
                modifier = Modifier.testTag("petGameReset").semantics { contentDescription = resetLabel }) {
                GameIcon(GameGlyph.RESET, light)
            }
            val lockLabel = stringResource(if (locked) R.string.pet_game_unlock else R.string.pet_game_lock)
            IconButton(onClick = onToggleLock, enabled = active, modifier = Modifier.testTag("petGameLock")
                .semantics { contentDescription = lockLabel }) {
                GameIcon(if (locked) GameGlyph.LOCKED else GameGlyph.UNLOCKED, if (locked) Color(0xFFFFD17C) else light)
            }
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = background) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().pointerInput(session, active, paused) {
                    if (active && !paused && session != null) detectTapGestures { session.steer(it.x / size.width) }
                }.testTag("petGameBoard")) {
                    val time = frame
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF261B40), Color(0xFF101A2A))))
                    repeat(34) { index ->
                        val x = ((index * 37 % 101) / 101f) * size.width
                        val y = ((index * 61 % 103) / 103f) * size.height
                        val alpha = .25f + .15f * sin(time * 1.6f + index)
                        drawCircle(lavender.copy(alpha = alpha), (1 + index % 2).dp.toPx(), Offset(x, y))
                    }
                    drawLine(lavender.copy(alpha = .22f), Offset(0f, size.height * .91f), Offset(size.width, size.height * .91f))
                    session?.shots?.forEach { shot ->
                        val tip = Offset(shot.x * size.width, shot.y * size.height)
                        val trail = tip + Offset(0f, 12.dp.toPx())
                        val color = if (shot.boosted) Color(0xFFF9CCFF) else Color(0xFF91F8DD)
                        drawLine(color.copy(alpha = .22f), tip, trail, 6.dp.toPx(), StrokeCap.Round)
                        drawLine(color, tip, trail, 2.dp.toPx(), StrokeCap.Round)
                    }
                    session?.drops?.forEach { drop ->
                        val point = Offset(drop.x * size.width, drop.y * size.height)
                        val radius = 11.dp.toPx()
                        val color = bonusColor(drop.kind)
                        drawCircle(color.copy(alpha = .17f), radius * (1.55f + sin(time * 8f) * .18f), point)
                        drawCircle(color, radius, point, style = Stroke(2.dp.toPx()))
                        drawGameGlyph(drop.kind.glyph, color, point, radius * .65f)
                    }
                    session?.bursts?.forEach { burst ->
                        val progress = ((time - burst.started) / .42f).coerceIn(0f, 1f)
                        val center = Offset(burst.x * size.width, burst.y * size.height)
                        val color = (burst.bonus?.let(::bonusColor) ?: Color(0xFFFFCD75)).copy(alpha = 1f - progress)
                        val radius = (5f + progress * 26f).dp.toPx()
                        drawCircle(color, radius, center, style = Stroke(1.dp.toPx()))
                        repeat(8) { index ->
                            val angle = index * .785398f
                            drawCircle(color, (2.5f - progress).dp.toPx(),
                                center + Offset(cos(angle) * radius, sin(angle) * radius))
                        }
                    }
                    if (session?.shield == true) {
                        val radius = 28.dp.toPx()
                        val shieldCenter = Offset(session.x * size.width, size.height * .95f - 23.dp.toPx())
                        val color = bonusColor(PetGame.Bonus.SHIELD)
                        val ring = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(color.copy(alpha = .22f), radius, shieldCenter, style = ring)
                        drawArc(color, -90f, session.shieldFraction * 360f, false,
                            topLeft = shieldCenter - Offset(radius, radius), size = Size(radius * 2f, radius * 2f), style = ring)
                    }
                    session?.flies?.forEach { fly ->
                        val point = Offset(fly.x(time) * size.width, fly.y * size.height)
                        val unit = 5.dp.toPx()
                        val flap = .6f + .35f * sin(time * 26f + fly.phase)
                        drawOval(Color(0xAAE3C6FF), point - Offset(unit * 1.7f, unit * flap), Size(unit * 1.7f, unit * 1.4f))
                        drawOval(Color(0xAAE3C6FF), point - Offset(0f, unit * flap), Size(unit * 1.7f, unit * 1.4f))
                        drawOval(Color(0xFFFFCD75), point - Offset(unit * .5f, unit * .7f), Size(unit, unit * 1.7f))
                    }
                    drawCharacter(PetUiState(ready = true, visible = true, appearance = appearance,
                        feet = Offset((session?.x ?: .5f) * size.width, size.height * .95f),
                        sizePx = 48.dp.toPx().coerceAtMost(size.height * .12f), animationSeconds = time))
                }
                if (session != null && lives == 0) {
                    PetGameResult(session, game.scoreSave, ranking, active, model::saveGameScore, game::start)
                } else if (session == null && ranking.isNotEmpty()) {
                    GameRankingCard {
                        RankingList(ranking)
                        Button(onClick = game::start, enabled = active, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.pet_game_start))
                        }
                    }
                } else if (session == null) {
                    val label = stringResource(R.string.pet_game_start)
                    FilledIconButton(enabled = active, onClick = game::start,
                        modifier = Modifier.size(72.dp).testTag("petGameStart").semantics { contentDescription = label }) {
                        GameIcon(GameGlyph.PLAY, LocalContentColor.current, Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PetGameResult(
    round: PetGame,
    save: PetScoreSave,
    ranking: List<PetGameScore>,
    active: Boolean,
    onSave: (String) -> Unit,
    onReplay: () -> Unit,
) {
    var nickname by rememberSaveable(round.id) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val saving = save == PetScoreSave.SAVING
    GameRankingCard {
        Text(stringResource(R.string.pet_game_finished), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.pet_game_result_score, round.score), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("petGameResultScore"))
        if (save != PetScoreSave.SAVED && save != PetScoreSave.BELOW_RANKING) {
            OutlinedTextField(nickname, { nickname = it.filter(Char::isLetter).take(3) },
                label = { Text(stringResource(R.string.pet_game_nickname)) },
                enabled = active && !saving, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("petGameNickname"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            Button(onClick = { keyboard?.hide(); onSave(nickname) }, enabled = active && nickname.length == 3 && !saving,
                modifier = Modifier.align(Alignment.End).testTag("petGameSaveScore")) {
                Text(stringResource(R.string.pet_game_save_score))
            }
        }
        when (save) {
            PetScoreSave.SAVED -> Text(stringResource(R.string.pet_game_score_saved), color = Color(0xFF8AF4EC))
            PetScoreSave.BELOW_RANKING -> Text(stringResource(R.string.pet_game_below_ranking))
            PetScoreSave.FAILED -> Text(stringResource(R.string.pet_game_score_failed), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        RankingList(ranking)
        TextButton(onClick = { keyboard?.hide(); onReplay() }, enabled = active && !saving,
            modifier = Modifier.align(Alignment.End).testTag("petGameStart")) {
            Text(stringResource(R.string.pet_game_retry))
        }
    }
}

@Composable
private fun GameRankingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.padding(12.dp).widthIn(max = 380.dp).fillMaxWidth().testTag("petGameRankingCard"),
        shape = RoundedCornerShape(22.dp), color = Color(0xFF28203D), contentColor = Color(0xFFF2E9FF)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun RankingList(ranking: List<PetGameScore>) {
    Text(stringResource(R.string.pet_game_ranking), style = MaterialTheme.typography.titleMedium)
    if (ranking.isEmpty()) Text(stringResource(R.string.pet_game_ranking_empty), style = MaterialTheme.typography.bodySmall)
    ranking.forEachIndexed { index, entry ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}.", Modifier.width(30.dp), color = Color(0xFFCFBCED))
            Text(entry.nickname, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(entry.points.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class GameGlyph { POINTS, HEART, RAPID, DOUBLE, SHIELD, LOCKED, UNLOCKED, PLAY, PAUSE, RESET }

private val PetGame.Bonus.glyph get() = when (this) {
    PetGame.Bonus.RAPID -> GameGlyph.RAPID
    PetGame.Bonus.DOUBLE -> GameGlyph.DOUBLE
    PetGame.Bonus.SHIELD -> GameGlyph.SHIELD
}

@Composable
private fun GameCounter(glyph: GameGlyph, value: Int, label: String, color: Color) {
    Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        GameIcon(glyph, color, Modifier.size(16.dp))
        Text(value.toString(), color = color, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BonusIcon(bonus: PetGame.Bonus, label: String, seconds: Int? = null) {
    val color = bonusColor(bonus)
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
        drawGameGlyph(bonus.glyph, color, center, size.minDimension * .31f)
        if (seconds != null) drawArc(color.copy(alpha = .85f), -90f, seconds / BONUS_DURATION * 360f, false,
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
            style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun GameIcon(glyph: GameGlyph, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) { drawGameGlyph(glyph, color, center, size.minDimension * .42f) }
}

private fun DrawScope.drawGameGlyph(glyph: GameGlyph, color: Color, center: Offset, radius: Float) {
    val unit = radius / 10f
    fun point(x: Int, y: Int) = center + Offset(x * unit, y * unit)
    fun line(x: Int, y: Int, endX: Int, endY: Int) =
        drawLine(color, point(x, y), point(endX, endY), unit * 1.8f, StrokeCap.Round)
    fun polygon(vararg vertices: Pair<Int, Int>) {
        val path = Path()
        vertices.forEachIndexed { index, (x, y) ->
            val vertex = point(x, y)
            if (index == 0) path.moveTo(vertex.x, vertex.y) else path.lineTo(vertex.x, vertex.y)
        }
        path.close()
        drawPath(path, color)
    }
    when (glyph) {
        GameGlyph.POINTS -> polygon(0 to -10, 3 to -3, 10 to -2, 5 to 3, 6 to 10, 0 to 6, -6 to 10, -5 to 3, -10 to -2, -3 to -3)
        GameGlyph.HEART -> {
            drawCircle(color, unit * 5f, point(-4, -3))
            drawCircle(color, unit * 5f, point(4, -3))
            polygon(-8 to -1, 8 to -1, 0 to 9)
        }
        GameGlyph.RAPID -> polygon(2 to -10, -7 to 1, -1 to 1, -3 to 10, 7 to -2, 1 to -2)
        GameGlyph.DOUBLE -> for (x in listOf(-4, 4)) {
            line(x, 8, x, -7); line(x - 2, -4, x, -7); line(x + 2, -4, x, -7)
        }
        GameGlyph.SHIELD -> polygon(0 to -9, 8 to -6, 7 to 2, 4 to 7, 0 to 10, -4 to 7, -7 to 2, -8 to -6)
        GameGlyph.LOCKED, GameGlyph.UNLOCKED -> {
            val left = if (glyph == GameGlyph.LOCKED) -4 else 0
            drawArc(color, 180f, 180f, false, topLeft = point(left, -9), size = Size(unit * 8, unit * 10),
                style = Stroke(unit * 1.8f, cap = StrokeCap.Round))
            if (glyph == GameGlyph.LOCKED) { line(-4, -4, -4, 0); line(4, -4, 4, 0) }
            else line(0, -4, 0, 0)
            drawRoundRect(color, point(-7, -1), Size(unit * 14, unit * 10), androidx.compose.ui.geometry.CornerRadius(unit * 2))
        }
        GameGlyph.PLAY -> polygon(-6 to -9, 9 to 0, -6 to 9)
        GameGlyph.PAUSE -> { line(-4, -7, -4, 7); line(4, -7, 4, 7) }
        GameGlyph.RESET -> {
            drawArc(color, -105f, -285f, false, topLeft = point(-7, -7), size = Size(unit * 14, unit * 14),
                style = Stroke(unit * 1.8f, cap = StrokeCap.Round))
            line(7, -7, 7, -1); line(7, -1, 1, -1)
        }
    }
}

private fun bonusColor(kind: PetGame.Bonus) = when (kind) {
    PetGame.Bonus.RAPID -> Color(0xFFFFD17C)
    PetGame.Bonus.DOUBLE -> Color(0xFFFFAAF1)
    PetGame.Bonus.SHIELD -> Color(0xFF8AF4EC)
}
