package pl.violetglass.launcher.pet

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PetGameTest {
    @Test fun resetRestartsPausedRoundAndRemovesItsBoosts() {
        val session = PetGameSession()
        session.start()
        val first = requireNotNull(session.round)
        first.drops.add(PetGame.Drop(PetGame.Bonus.SHIELD, .5f, .90f))
        session.step(.05f)
        session.pause()
        repeat(220) { session.step(.05f) }
        assertEquals(9, first.shieldSeconds)
        session.start()
        val second = requireNotNull(session.round)
        assertNotEquals(first.id, second.id)
        assertFalse(session.paused)
        assertEquals(0f, second.time, 0f)
        assertEquals(3, second.lives)
        assertFalse(second.shield)
    }

    @Test fun laterInTheRoundMoreFliesSpawnAndTheShieldExpiresOnGameTime() {
        val game = PetGame(Random(19))
        fun countSpawns(frames: Int): Int = (1..frames).sumOf {
            game.step(.05f)
            game.flies.size.also { game.flies.clear() }
        }
        val opening = countSpawns(200)
        countSpawns(1600)
        val later = countSpawns(200)
        assertTrue(later > opening * 2)
        game.drops.add(PetGame.Drop(PetGame.Bonus.SHIELD, .5f, .90f))
        game.step(.05f)
        assertEquals(1f, game.shieldFraction, .001f)
        countSpawns(90)
        assertEquals(.5f, game.shieldFraction, .002f)
        countSpawns(100)
        assertFalse(game.shield)
    }

    @Test fun rankingKeepsTenBestDistinctRoundsAndBreaksTiesByDate() {
        val entries = (1..11).map { PetGameScore("round-$it", "VIO", it * 10, 1L) }
        val tied = PetGameScore("earlier", "ŁAP", 110, 0L)
        val ranking = rankPetGameScores(entries + tied + entries.last())
        assertEquals(10, ranking.size)
        assertEquals(listOf("earlier", "round-11"), ranking.take(2).map { it.roundId })
        assertEquals(30, ranking.last().points)
        assertEquals("ŁĄB", normalizePetNickname(" ł2ą-bc"))
        assertEquals("XY", normalizePetNickname("x1 y"))
    }

    @Test fun scoresCanOnlyBeSubmittedOncePerCompletedRound() {
        val session = PetGameSession()
        session.start()
        assertNull(session.beginSaving())
        val round = requireNotNull(session.round)
        repeat(3) { round.flies.add(PetGame.Fly(.15f, 0f, 0f, y = .93f)) }
        session.step(.016f)
        assertSame(round, session.beginSaving())
        assertNull(session.beginSaving())
        session.finishSaving(round, PetScoreSave.FAILED)
        assertSame(round, session.beginSaving())
        session.finishSaving(round, PetScoreSave.SAVED)
        assertNull(session.beginSaving())
    }

    @Test fun pausingKeepsTheSameRoundAndStopsItsClock() {
        val session = PetGameSession()
        session.start()
        val round = requireNotNull(session.round)
        session.step(.02f)
        val elapsed = round.time
        session.pause()
        repeat(100) { session.step(.02f) }
        assertEquals(elapsed, round.time, 0f)
        assertSame(round, session.round)
        session.togglePause()
        session.step(.02f)
        assertEquals(elapsed + .02f, round.time, .0001f)
    }

    @Test fun steeringAcrossTheBoardMovesGraduallyAndStopsAtTheTarget() {
        val game = PetGame(Random(1))
        game.steer(1f)
        game.step(.016f)
        assertTrue(game.x in .5f..0.516f)
        repeat(14) { game.step(.016f) }
        assertTrue(game.x < .72f)
        repeat(100) { game.step(.016f) }
        assertEquals(.91f, game.x, .003f)
    }

    @Test fun collectedBonusesStackAndShieldConsumesOneMiss() {
        val game = PetGame(Random(1))
        PetGame.Bonus.entries.forEach { game.drops.add(PetGame.Drop(it, .5f, .90f)) }
        game.step(.016f)
        assertEquals(9, game.rapidSeconds)
        assertEquals(9, game.doubleSeconds)
        assertTrue(game.shield)
        assertTrue(game.drops.isEmpty())
        val before = game.shots.size
        repeat(10) { game.step(.016f) }
        assertTrue(game.shots.size >= before + 4)
        assertTrue(game.shots.count { it.boosted } >= 4)
        game.flies.add(PetGame.Fly(.15f, 0f, 0f, y = .93f))
        game.step(.016f)
        assertEquals(3, game.lives)
        assertFalse(game.shield)
        repeat(200) {
            game.flies.clear()
            game.step(.05f)
        }
        assertEquals(0, game.rapidSeconds)
        assertEquals(0, game.doubleSeconds)
    }

    @Test fun projectileScoresAndMissedFliesEndTheRound() {
        val game = PetGame(Random(1))
        game.flies.add(PetGame.Fly(.5f, 0f, 0f, y = .74f))
        repeat(12) { game.step(.01f) }
        assertEquals(10, game.score)
        assertTrue(game.flies.isEmpty())
        repeat(3) { game.flies.add(PetGame.Fly(.15f, 0f, 0f, y = .93f)) }
        game.step(.016f)
        assertEquals(0, game.lives)
        val stopped = game.time
        game.step(.016f)
        assertEquals(stopped, game.time, 0f)
    }

    @Test fun desktopHopReversesWithThePagerAndReturnsToItsOriginalFeet() {
        val origin = Offset(140f, 410f)
        val end = PetUiState(ready = true, visible = true, feet = Offset(140f, 410f), sizePx = 40f)
        val right = end.acrossDesktop(origin, .5f, 400f)
        val left = end.acrossDesktop(origin, -.5f, 400f)
        assertEquals(PetMode.HOPPING, right.mode)
        assertTrue(right.feet.x > origin.x && left.feet.x < origin.x)
        assertTrue(right.feet.y < origin.y)
        assertEquals(origin, end.acrossDesktop(origin, 0f, 400f).feet)
        assertEquals(end, end.acrossDesktop(origin, 1f, 400f))
    }
}
