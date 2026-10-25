package com.metrolist.music.playback

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// SARASWATI: exhaustive voice phrasing. Every row is a voice query through the real onSetMediaItems, built into a queue
// and never played. Rows go to logcat under VOICEPHRASE as they are measured; the assertion runs at the end so one
// failure does not hide the rest. A run of three consecutive empty results stops the suite (lockout signal).

private const val PHRASE_TAG = "VOICEPHRASE"
private const val PERSON_MIN = 10
private const val MOVIE_MIN = 5
private const val NAMED_MIN = 2
private const val EXACT_TITLE_MIN_SCORE = 0.90

private sealed interface Rule {
    // A real queue of at least [min] tracks.
    data class Queue(val min: Int) : Rule

    // A real queue, and the named song first (VoiceSearchMatcher.findBest on the first track).
    data class NamedFirst(val min: Int) : Rule

    // The named song first, with a strong title score (rankAll >= EXACT_TITLE_MIN_SCORE).
    data object Exact : Rule

    // Only requires that the call returns and the service stays connected. An empty result is a pass.
    data object NoCrash : Rule
}

private data class PhraseCase(val group: String, val query: String, val rule: Rule)

private fun cases(group: String, rule: Rule, queries: List<String>) = queries.map { PhraseCase(group, it, rule) }

// The singer, director, actor, lyricist rows of the earlier matrix. Run twice to see whether results repeat.
private val PERSON_QUERIES = listOf(
    "sonu nigam songs",
    "arijit singh songs",
    "lata mangeshkar songs",
    "kishore kumar songs",
    "shreya ghoshal songs",
    "a r rahman songs",
    "pritam songs",
    "shahrukh khan songs",
    "salman khan songs",
    "deepika padukone songs",
    "madhuri dixit songs",
    "gulzar songs",
    "javed akhtar songs",
    "karan johar songs",
)

private val PLAN: List<PhraseCase> =
    cases(
        "A artist", Rule.Queue(10),
        listOf(
            "sonu nigam", "sonu nigam songs", "songs by sonu nigam", "shuffle sonu nigam",
            "sonu nigam radio", "some arijit singh music", "arijit singh top songs",
        ),
    ) +
        cases(
            "B song+artist", Rule.NamedFirst(NAMED_MIN),
            listOf("tum hi ho by arijit singh", "kal ho naa ho by sonu nigam"),
        ) +
        cases(
            "C song+movie", Rule.NamedFirst(NAMED_MIN),
            listOf("tum hi ho from aashiqui 2", "chaiyya chaiyya from dil se"),
        ) +
        cases(
            "D movie/album", Rule.Queue(MOVIE_MIN),
            listOf("dilwale dulhania le jayenge", "lagaan songs", "3 idiots soundtrack"),
        ) +
        cases(
            "E hindi/hinglish", Rule.Queue(PERSON_MIN),
            listOf(
                "lata mangeshkar ke gaane", "kishore kumar ka gana", "purane hindi gaane",
                "लता मंगेशकर के गाने", "मोहम्मद रफ़ी के गाने",
            ),
        ) +
        cases(
            "F genre/mood", Rule.Queue(PERSON_MIN),
            listOf("bollywood music", "old hindi songs", "bhajans", "90s bollywood songs"),
        ) +
        cases(
            "I exact title", Rule.Exact,
            listOf("tum hi ho", "kal ho naa ho", "chaiyya chaiyya", "o palan hare"),
        ) +
        cases("PERSON pass 1", Rule.Queue(PERSON_MIN), PERSON_QUERIES) +
        cases("PERSON pass 2", Rule.Queue(PERSON_MIN), PERSON_QUERIES) +
        cases(
            "G personal library", Rule.NoCrash,
            listOf("my liked songs", "downloaded songs", "my playlist"),
        ) +
        cases(
            "H hostile", Rule.NoCrash,
            listOf(
                "", "   ", "asdkjhasdkjh", "om", "a", "tum hi ho ".repeat(50), "🎵🎵🎵",
                "'; DROP TABLE songs;--", "../../etc/passwd",
            ),
        )

private data class PhraseRow(
    val case: PhraseCase,
    val snapshot: QueueSnapshot?,
    val elapsedMs: Long,
    val score: Double?,
    val artistAsked: Boolean?,
    val failure: String?,
)

private class Judgement(val failure: String?, val score: Double?, val artistAsked: Boolean?)

@RunWith(AndroidJUnit4::class)
class VoicePhrasingInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = AaVoiceSession(instrumentation, PHRASE_TAG)

    @Test
    fun voicePhrasing() {
        session.connect()
        val rows = mutableListOf<PhraseRow>()
        try {
            val streak = EmptyStreak()
            var stopped: String? = null
            for (case in PLAN) {
                if (stopped != null) {
                    rows += PhraseRow(case, null, 0L, null, null, "NOT RUN: $stopped")
                    continue
                }
                val row = runCase(case)
                rows += row
                if (case.rule != Rule.NoCrash && streak.record(row.snapshot?.count == 0)) {
                    stopped = "three consecutive empty results, last '${case.query}'"
                    Log.w(PHRASE_TAG, "STOP\t$stopped")
                }
            }
        } finally {
            session.release()
        }
        logSummary(rows)
        val failures = rows.filter { it.failure != null }
        assertTrue(
            "${failures.size} of ${rows.size} voice phrasings failed: " +
                failures.joinToString("; ") { "${it.case.group} '${it.case.query}' -> ${it.failure}" },
            failures.isEmpty(),
        )
    }

    private fun runCase(case: PhraseCase): PhraseRow {
        val cleared = session.resetQueue()
        session.submitVoice(case.query)
        val started = System.currentTimeMillis()
        val snap = try {
            session.awaitQueue()
        } catch (e: Throwable) {
            Log.i(PHRASE_TAG, "ROW\t${case.group}\t${case.query}\tthrew=${e.javaClass.name}: ${e.message}")
            idle()
            return PhraseRow(case, null, System.currentTimeMillis() - started, null, null, "threw ${e.javaClass.name}: ${e.message}")
        }
        val elapsed = System.currentTimeMillis() - started
        val alive = session.isConnected()
        val judged = judge(case, snap, cleared, alive)
        Log.i(
            PHRASE_TAG,
            "ROW\t${case.group}\t${case.query}\tcount=${snap.count}\tfirst=${snap.first}\tfirstArtist=${snap.firstArtist}\t" +
                "score=${judged.score.formatScore()}\tartistAsked=${judged.artistAsked ?: "-"}\talive=$alive\tms=$elapsed\t" +
                (judged.failure ?: "ok"),
        )
        idle()
        return PhraseRow(case, snap, elapsed, judged.score, judged.artistAsked, judged.failure)
    }

    private fun judge(case: PhraseCase, snap: QueueSnapshot, cleared: Boolean, alive: Boolean): Judgement {
        val artistAsked = if (case.group.startsWith("PERSON")) artistAskedFor(case.query, snap.firstArtist) else null
        val named = VoiceSearchMatcher.findBest(case.query, listOf(candidateSong(snap)))
        val score = if (snap.count > 0 && snap.first != "-") {
            VoiceSearchMatcher.rankAll(case.query, listOf(candidateSong(snap))).firstOrNull()?.score
        } else {
            null
        }
        val failure = when (case.rule) {
            Rule.NoCrash -> if (!alive) "controller disconnected" else null
            is Rule.Queue -> when {
                !alive -> "controller disconnected"
                !cleared -> "stale queue was not cleared before the query"
                snap.count < case.rule.min -> "count=${snap.count} is below ${case.rule.min}"
                else -> null
            }
            is Rule.NamedFirst -> when {
                !alive -> "controller disconnected"
                !cleared -> "stale queue was not cleared before the query"
                snap.count < case.rule.min -> "count=${snap.count} is below ${case.rule.min}"
                named == null -> "first track '${snap.first}' is not the named song"
                else -> null
            }
            Rule.Exact -> when {
                !alive -> "controller disconnected"
                !cleared -> "stale queue was not cleared before the query"
                snap.first == "-" -> "no first track title"
                named == null -> "first track '${snap.first}' is not the named song"
                (score ?: 0.0) < EXACT_TITLE_MIN_SCORE ->
                    "first title '${snap.first}' scores ${score.formatScore()}, below $EXACT_TITLE_MIN_SCORE"
                else -> null
            }
        }
        return Judgement(failure, score, artistAsked)
    }

    // "a r rahman songs" -> "arrahman" must appear in the first track's artist with spaces and punctuation removed.
    private fun artistAskedFor(query: String, firstArtist: String): Boolean {
        val asked = query.removeSuffix(" songs").lowercase().filter { it.isLetter() }
        val got = firstArtist.lowercase().filter { it.isLetter() }
        return asked.isNotEmpty() && got.contains(asked)
    }

    private fun Double?.formatScore(): String = this?.let { "%.3f".format(it) } ?: "-"

    private fun logSummary(rows: List<PhraseRow>) {
        rows.forEach { row ->
            val status = if (row.failure == null) "PASS" else "FAIL"
            val snap = row.snapshot
            Log.i(
                PHRASE_TAG,
                "RESULT\t$status\t${row.case.group}\t${row.case.query}\tcount=${snap?.count ?: "-"}\t" +
                    "first=${snap?.first ?: "-"}\tfirstArtist=${snap?.firstArtist ?: "-"}\t" +
                    "score=${row.score.formatScore()}\tartistAsked=${row.artistAsked ?: "-"}\tms=${row.elapsedMs}\t${row.failure ?: "ok"}",
            )
        }
        val failed = rows.count { it.failure != null }
        Log.i(PHRASE_TAG, "SUMMARY\trows=${rows.size}\tfailed=$failed\tpassed=${rows.size - failed}")
    }
}
