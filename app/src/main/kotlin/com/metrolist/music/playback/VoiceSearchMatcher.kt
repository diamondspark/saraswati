package com.metrolist.music.playback

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import kotlin.math.abs

object VoiceSearchMatcher {
    const val STRONG_MATCH_THRESHOLD = 0.60
    private val PUNCTUATION_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    const val FUZZY_THRESHOLD = 0.85
    // SARASWATI: voice queries carry filler words (Google Assistant "by", "songs", Hindi "ka"), ignored on both sides
    private val STOPWORDS = setOf(
        "by", "from", "the", "of", "a", "an", "song", "songs", "play", "please", "movie", "film", "album",
        "playlist", "ka", "ke", "ki", "gaane", "gane", "gaana", "gana",
    )
    // SARASWATI: matches the word "from" in "<title> from <movie>" voice queries
    private val FROM_REGEX = Regex("\\s+from\\s+")
    // SARASWATI: matches "(...)" and "[...]" segments in song titles such as "(From \"Movie\")"
    private val BRACKET_SEGMENT_REGEX = Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]")
    // SARASWATI: matches a run of one repeated letter, used to collapse vowel-length variants (aa -> a); digits are kept
    private val REPEATED_CHAR_REGEX = Regex("(\\p{L})\\1+")
    // SARASWATI: score for a title equal to the query only after stopwords are dropped (raw exact stays 1.0)
    private const val STOPWORD_ONLY_SCORE = 0.99
    // SARASWATI: discount for a match that only holds on the cleaned title, so the plain title ranks first
    private const val CLEANED_TITLE_FACTOR = 0.99
    // SARASWATI: cap for matches against the part of a query before " from ", so a bare title never ties the full query
    private const val FROM_SPLIT_VARIANT_CAP = 0.95

    data class ScoredSong(
        val song: Song,
        val score: Double
    )

    // SARASWATI: ranks candidates by their best-matching query variant, with the exact-match and stopword caps
    fun rankAll(query: String, candidates: List<Song>): List<ScoredSong> {
        val queryLower = query.lowercase().trim()
        val queryTokens = significantTokens(queryLower, fallbackToAll = false)
        if (queryTokens.isEmpty()) return emptyList()

        val variants = queryVariants(queryLower, queryTokens)

        return candidates
            .map { song ->
                // SARASWATI: the first variant is the full query; split-off variants are capped below an exact match
                val score = variants.withIndex().maxOf { (index, variant) ->
                    val (text, tokens) = variant
                    val variantScore = scoreCandidate(song, text, tokens, isQueryTooGeneric(text, tokens))
                    if (index == 0) variantScore else minOf(variantScore, FROM_SPLIT_VARIANT_CAP)
                }
                ScoredSong(song, score)
            }
            .sortedByDescending { it.score }
    }

    fun findBest(query: String, candidates: List<Song>): Song? {
        val ranks = rankAll(query, candidates)
        return ranks.firstOrNull { it.score >= STRONG_MATCH_THRESHOLD }?.song
    }

    /**
     * Resolves a voice query that is just an artist's name (e.g. "play Arijit Singh"),
     * unlike [findBest], which matches song titles. Returns the best candidate only if it
     * clears [STRONG_MATCH_THRESHOLD], else null.
     */
    // SARASWATI: fork feature — voice resolver for artist-only queries, now delegates to findBestByName
    fun findBestArtist(query: String, candidates: List<ArtistEntity>): ArtistEntity? =
        findBestByName(query, candidates) { it.name }

    // SARASWATI: generalised from findBestArtist so playlist/album names resolve from voice queries too
    fun <T> findBestByName(query: String, candidates: List<T>, nameOf: (T) -> String): T? {
        val queryLower = query.lowercase().trim()
        val queryTokens = significantTokens(queryLower, fallbackToAll = false)
        if (queryTokens.isEmpty()) return null

        val isTooGeneric = isQueryTooGeneric(queryLower, queryTokens)

        var best: T? = null
        var bestScore = 0.0
        candidates.forEach { candidate ->
            val nameLower = nameOf(candidate).lowercase().trim()
            val nameTokens = significantTokens(nameLower, fallbackToAll = true)

            val score = when {
                nameLower == queryLower || tokenize(nameLower) == tokenize(queryLower) -> 1.0
                nameTokens == queryTokens -> STOPWORD_ONLY_SCORE
                isTooGeneric -> 0.0
                else -> {
                    // Reject partial-name coverage ("arijit" vs "Arijit Singh") before scoring
                    val nameFullyMatched = nameTokens.isNotEmpty() && nameTokens.all { nameToken ->
                        queryTokens.any { q -> q == nameToken || jaroWinkler(q, nameToken) >= FUZZY_THRESHOLD }
                    }
                    val base = if (nameFullyMatched) fuzzyScore(queryTokens, nameTokens) else 0.0
                    maxOf(base, compactScore(queryTokens, nameTokens))
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        return best?.takeIf { bestScore >= STRONG_MATCH_THRESHOLD }
    }

    // SARASWATI: "<title> from <movie>" queries also try the part before " from " as a title
    private fun queryVariants(queryLower: String, queryTokens: Set<String>): List<Pair<String, Set<String>>> {
        val variants = mutableListOf(queryLower to queryTokens)
        FROM_REGEX.find(queryLower)?.let { match ->
            val beforeText = queryLower.substring(0, match.range.first).trim()
            val beforeTokens = significantTokens(beforeText, fallbackToAll = false)
            if (beforeTokens.isNotEmpty()) variants += beforeText to beforeTokens
        }
        return variants
    }

    // SARASWATI: same short-query guard findBestArtist used, shared by every query variant
    private fun isQueryTooGeneric(queryLower: String, queryTokens: Set<String>): Boolean =
        (queryTokens.size == 1 && queryTokens.first().length < 4) || queryLower.length < 5

    // SARASWATI: scores a song against each title form (full, cleaned) and keeps the better one
    private fun scoreCandidate(
        song: Song,
        queryLower: String,
        queryTokens: Set<String>,
        isTooGeneric: Boolean,
    ): Double {
        val title = song.title.lowercase().trim()
        // SARASWATI: score against the full title and the cleaned title ("Song (From ...)" -> "Song"); the cleaned score is discounted so the plain title ranks first
        val cleanedTitle = cleanTitle(title)
        val fullScore = scoreTitle(song, title, queryLower, queryTokens, isTooGeneric)
        if (cleanedTitle == title) return fullScore
        val cleanedScore = scoreTitle(song, cleanedTitle, queryLower, queryTokens, isTooGeneric) * CLEANED_TITLE_FACTOR
        return maxOf(fullScore, cleanedScore)
    }

    // SARASWATI: the scoring rules for one form of the title, split out so scoreCandidate can apply them twice
    private fun scoreTitle(
        song: Song,
        title: String,
        queryLower: String,
        queryTokens: Set<String>,
        isTooGeneric: Boolean,
    ): Double {
        val titleTokens = significantTokens(title, fallbackToAll = true)

        if (title == queryLower || tokenize(title) == tokenize(queryLower)) return 1.0
        // SARASWATI: equal only once stopwords are dropped, so it must rank below a raw exact match
        if (titleTokens == queryTokens) return STOPWORD_ONLY_SCORE

        val remaining = stripArtistTokens(queryTokens, song)

        if (remaining.isEmpty()) return 0.5     // The query was purely artist name, so return moderate confidence
        if (titleTokens == remaining) return 0.95
        if (isTooGeneric) return 0.0

        return maxOf(fuzzyScore(remaining, titleTokens), compactScore(remaining, titleTokens))
    }

    // SARASWATI: drops "(...)"/"[...]" segments and everything after " - " (e.g. "Song - Happy" -> "Song")
    internal fun cleanTitle(title: String): String =
        BRACKET_SEGMENT_REGEX.replace(title, "").substringBefore(" - ").trim()

    private fun stripArtistTokens(queryTokens: Set<String>, song: Song): Set<String> {
        val remaining = queryTokens.toMutableSet()
        song.artists.forEach { artist ->
            val artistTokens = tokenize(artist.name.lowercase())
            remaining.removeIf { queryToken ->
                if (queryToken.length < 4) artistTokens.contains(queryToken)
                else artistTokens.any { artistToken -> jaroWinkler(queryToken, artistToken) >= 0.85 }
            }
        }
        return remaining
    }

    private fun fuzzyScore(queryTokens: Set<String>, titleTokens: Set<String>): Double {
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0.0

        val matchedQuery = queryTokens.count { q ->
            titleTokens.any { t -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
        }
        val matchedTitle = titleTokens.count { t ->
            queryTokens.any { q -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
        }

        if (matchedQuery == 0 || matchedTitle == 0) return 0.0

        val titleCoverage = matchedTitle.toDouble() / titleTokens.size
        val queryCoverage = matchedQuery.toDouble() / queryTokens.size
        return if (titleCoverage + queryCoverage > 0)
            (2.0 * titleCoverage * queryCoverage) / (titleCoverage + queryCoverage)   // harmonic mean
        else 0.0
    }

    // SARASWATI: whole-string match that ignores word splitting and vowel-length variants (Palan Hare vs Paalanhaare)
    internal fun compactScore(query: Collection<String>, title: Collection<String>): Double {
        val q = compactKey(query)
        val t = compactKey(title)
        if (q.length < 5 || t.length < 5) return 0.0
        // SARASWATI: digits are significant ("Part 11" is not "Part 1"), so differing digit sequences never match
        if (q.filter { it.isDigit() } != t.filter { it.isDigit() }) return 0.0
        if (q == t) return 0.95
        return if (withinEditDistance(q, t, 1)) 0.9 else 0.0
    }

    // SARASWATI: joins tokens without separators and collapses repeated letters, for compactScore
    internal fun compactKey(tokens: Collection<String>): String =
        REPEATED_CHAR_REGEX.replace(tokens.joinToString(""), "\$1")

    // SARASWATI: bounded Levenshtein check for compactScore; exits early once a whole row exceeds the limit
    private fun withinEditDistance(a: String, b: String, limit: Int): Boolean {
        if (abs(a.length - b.length) > limit) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > limit) return false
            previous = current
        }
        return previous[b.length] <= limit
    }

    // SARASWATI: tokens with filler stopwords removed; falls back to all tokens for titles that are only stopwords
    internal fun significantTokens(text: String, fallbackToAll: Boolean): Set<String> {
        val all = tokenize(text)
        val kept = all - STOPWORDS
        return if (kept.isEmpty() && fallbackToAll) all else kept
    }

    internal fun tokenize(text: String): Set<String> =
        PUNCTUATION_REGEX.replace(text.lowercase(), " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Token Similarity based on Jaro-Winkler Distance Algorithm
     */
    internal fun jaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        if (maxDist < 0) return 0.0

        var matches = 0
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }

        val jaro = (
                matches.toDouble() / s1.length +
                        matches.toDouble() / s2.length +
                        (matches - transpositions / 2.0) / matches
                ) / 3.0

        val prefix = s1.commonPrefixWith(s2).length.coerceAtMost(4)
        val p = 0.1
        val boostThreshold = 0.7
        return if (jaro > boostThreshold) jaro + (prefix * p * (1.0 - jaro)) else jaro
    }
}
