package pl.violetglass.launcher.pet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.petGameScoreStore by preferencesDataStore("pet_game_scores")

internal data class PetGameScore(val roundId: String, val nickname: String, val points: Int, val playedAt: Long)

internal fun normalizePetNickname(input: String): String =
    input.asSequence().filter(Char::isLetter).take(3).map(Char::uppercaseChar).joinToString("")

internal fun rankPetGameScores(scores: Iterable<PetGameScore>): List<PetGameScore> =
    scores.sortedWith(compareByDescending<PetGameScore> { it.points }.thenBy { it.playedAt }.thenBy { it.roundId })
        .distinctBy { it.roundId }
        .take(10)

internal class PetGameScoreRepository(private val store: DataStore<Preferences>) {
    private val entries = stringSetPreferencesKey("entries")
    val scores = store.data.map(::decode)

    suspend fun save(score: PetGameScore): Boolean {
        require(normalizePetNickname(score.nickname) == score.nickname && score.nickname.length == 3)
        val written = store.edit { prefs ->
            val current = decode(prefs)
            val next = if (current.any { it.roundId == score.roundId }) current else rankPetGameScores(current + score)
            prefs[entries] = next.mapTo(mutableSetOf()) { entry ->
                listOf(entry.roundId, entry.nickname, entry.points, entry.playedAt).joinToString(":")
            }
        }
        return decode(written).any { it.roundId == score.roundId }
    }

    private fun decode(prefs: Preferences): List<PetGameScore> = rankPetGameScores(
        prefs[entries].orEmpty().mapNotNull { raw ->
            val fields = raw.split(':')
            if (fields.size != 4) return@mapNotNull null
            val (id, nick, points, time) = fields
            if (id.isBlank() || nick.length != 3 || normalizePetNickname(nick) != nick) return@mapNotNull null
            val score = points.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val playedAt = time.toLongOrNull() ?: return@mapNotNull null
            PetGameScore(id, nick, score, playedAt)
        }
    )
}
