package com.metrolist.music.playback

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class VoiceSearchMatcherTest {
    private fun song(title: String, vararg artists: String): Song {
        val songEntity = SongEntity(
            id = title.replace(" ", "_").lowercase(),
            title = title,
            duration = 200,
            thumbnailUrl = null,
        )
        val artistEntities = artists.map { name ->
            ArtistEntity(id = name.lowercase(), name = name)
        }
        return Song(
            song = songEntity,
            artists = artistEntities,
            album = null,
        )
    }

    // Jaro-Winkler and Tokenization
    @Test
    fun `jaro-winkler and tokenizer edge cases`() {
        assertEquals("jw: identical strings", 1.0, VoiceSearchMatcher.jaroWinkler("hello", "hello"), 0.0)
        assertEquals("jw: empty vs non-empty", 0.0, VoiceSearchMatcher.jaroWinkler("", "hello"), 0.0)
        assertEquals("jw: non-empty vs empty", 0.0, VoiceSearchMatcher.jaroWinkler("hello", ""), 0.0)
        assertEquals("jw: both empty", 1.0, VoiceSearchMatcher.jaroWinkler("", ""), 0.0)
        assertTrue("jw: similar strings", VoiceSearchMatcher.jaroWinkler("martha", "marhta") > 0.9)
        assertEquals("jw: dissimilar strings", 0.0, VoiceSearchMatcher.jaroWinkler("abc", "xyz"), 0.0)
        assertTrue(
            "jw: boost threshold blocks low jaro",
            VoiceSearchMatcher.jaroWinkler("MARGHERITA", "MARMMELLATA") < 0.7,
        )
        assertEquals(
            "tokenize: strips punctuation",
            setOf("don", "t", "stop", "me", "now"),
            VoiceSearchMatcher.tokenize("Don't Stop Me Now!"),
        )
    }

    // Ranking
    @Test
    fun `exact match scores 1 and is order-independent`() {
        val candidates = listOf(
            song("Bohemian Rhapsody", "Queen"),
            song("Bohemian Like You", "Dandy Warhols"),
        )
        val ranked = VoiceSearchMatcher.rankAll("Bohemian Rhapsody", candidates)
        assertEquals("exact: best title", "Bohemian Rhapsody", ranked.first().song.title)
        assertEquals("exact: best score", 1.0, ranked.first().score, 0.0)

        val reversed = VoiceSearchMatcher.rankAll(
            "Pressure Under",
            listOf(song("Under Pressure", "Queen")),
            )
        assertEquals("exact: reversed order score", 1.0, reversed.first().score, 0.0)
    }
    @Test
    fun `artist tokens are stripped before scoring`() {
        val single = VoiceSearchMatcher.rankAll(
            "The Weeknd Blinding Lights",
            listOf(
                song("Blinding Lights", "The Weeknd"),
                song("Lights", "Ellie Goulding"),
            ),
        )
        assertEquals("artist-strip: best", "Blinding Lights", single.first().song.title)
        assertEquals("artist-strip: score", 0.95, single.first().score, 0.001)

        val multi = VoiceSearchMatcher.rankAll(
            "Queen David Bowie Under Pressure",
            listOf(song("Under Pressure", "Queen", "David Bowie")),
        )
        assertEquals("artist-strip multi: score", 0.95, multi.first().score, 0.001)

        val artistOnly = VoiceSearchMatcher.rankAll(
            "Queen",
            listOf(
                song("Song A", "Queen"),
                song("Song B", "Beatles"),
            ),
        )
        assertEquals("artist-only: score", 0.5, artistOnly.first().score, 0.001)
    }
    @Test
    fun `fuzzy matching and generic-query guard`() {
        val fuzzyBlocked = VoiceSearchMatcher.rankAll("Go", listOf(song("Go Away", "A")))
        assertEquals("generic: fuzzy blocked", 0.0, fuzzyBlocked.first().score, 0.0)
        val exactGeneric = VoiceSearchMatcher.rankAll("Go", listOf(song("Go", "A")))
        assertEquals("generic: exact still allowed", 1.0, exactGeneric.first().score, 0.0)

        val typo = VoiceSearchMatcher.rankAll(
            "Bohemian Rapsody",
            listOf(song("Bohemian Rhapsody", "Queen")),
        )
        assertTrue("fuzzy typo: above threshold", typo.first().score >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD)

        val punct = VoiceSearchMatcher.rankAll(
            "Dont Stop Me Now",
            listOf(song("Don't Stop Me Now!", "Some artist")),
        )
        assertTrue("punctuation: above threshold", punct.first().score >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD)
    }
    @Test
    fun `edge cases - no match, empty inputs, best not first`() {
        assertNull(
            "no-match: findBest returns null",
            VoiceSearchMatcher.findBest(
                "Bohemian Rhapsody",
                listOf(song("Completely Different", "Nobody"))
            ),
        )

        assertTrue("empty: no candidates", VoiceSearchMatcher.rankAll("Hello", emptyList()).isEmpty())
        assertTrue("empty: blank query", VoiceSearchMatcher.rankAll("", listOf(song("S", "A"))).isEmpty())

        val best = VoiceSearchMatcher.findBest(
            "Bohemian Rhapsody",
            listOf(
                song("Unrelated Song", "Nobody"),
                song("Bohemian Rhapsody", "Queen"),
                song("Another Song", "Someone"),
            ),
        )
        assertEquals("not-first: correct winner", "Bohemian Rhapsody", best?.title)
    }

    // findBestArtist: resolving a voice query that is just an artist's name
    @Test
    fun `artist-only query resolves the exact artist`() {
        val candidates = listOf(
            ArtistEntity(id = "ar1", name = "Arijit Singh"),
            ArtistEntity(id = "ar2", name = "Shreya Ghoshal"),
            ArtistEntity(id = "ar3", name = "Queen"),
        )
        val best = VoiceSearchMatcher.findBestArtist("Arijit Singh", candidates)
        assertEquals("artist: exact name", "Arijit Singh", best?.name)

        val reversed = VoiceSearchMatcher.findBestArtist("ghoshal shreya", candidates)
        assertEquals("artist: token order-independent exact", "Shreya Ghoshal", reversed?.name)
    }

    @Test
    fun `artist-only query with typo still resolves via fuzzy matching`() {
        val candidates = listOf(
            ArtistEntity(id = "ar1", name = "Arijit Singh"),
            ArtistEntity(id = "ar2", name = "Shreya Ghoshal"),
        )
        val typo = VoiceSearchMatcher.findBestArtist("Arjith Singh", candidates)
        assertEquals("artist-fuzzy: one token typo", "Arijit Singh", typo?.name)
    }

    @Test
    fun `song-title-only query does not falsely match an artist`() {
        val candidates = listOf(
            ArtistEntity(id = "ar1", name = "Arijit Singh"),
            ArtistEntity(id = "ar2", name = "Shreya Ghoshal"),
        )
        assertNull("artist-false: unrelated title", VoiceSearchMatcher.findBestArtist("Bohemian Rhapsody", candidates))
        assertNull("artist-false: partial token set", VoiceSearchMatcher.findBestArtist("Arijit", candidates))
    }

    @Test
    fun `artist-only query edge cases return null`() {
        val candidates = listOf(ArtistEntity(id = "ar1", name = "Arijit Singh"))

        assertNull("artist-edge: blank query", VoiceSearchMatcher.findBestArtist("   ", candidates))
        assertNull(
            "artist-edge: generic short query (no exact artist)",
            VoiceSearchMatcher.findBestArtist("Go", candidates),
        )
        assertNull("artist-edge: empty candidate list", VoiceSearchMatcher.findBestArtist("Arijit Singh", emptyList()))
    }

    // Regression: song-title matching must be completely unaffected by the artist resolver
    @Test
    fun `regression - findBest song matching is untouched by artist resolver`() {
        val candidates = listOf(
            song("Blinding Lights", "The Weeknd"),
            song("Lights", "Ellie Goulding"),
        )
        val best = VoiceSearchMatcher.findBest("The Weeknd Blinding Lights", candidates)
        assertEquals("regression: artist-prefixed title", "Blinding Lights", best?.title)

        val pureArtist = VoiceSearchMatcher.findBest("Queen", listOf(song("Song A", "Queen"), song("Wrong", "Who")))
        assertNull("regression: pure artist query still below song threshold", pureArtist)

        val typo = VoiceSearchMatcher.findBest("Bohemian Rapsody", listOf(song("Bohemian Rhapsody", "Queen")))
        assertEquals("regression: fuzzy title still resolves", "Bohemian Rhapsody", typo?.title)
    }

    // Voice-query handling: stopwords, "<title> from <movie>", cleaned titles, transliteration
    @Test
    fun `stopword by is ignored on the query side`() {
        val candidates = listOf(
            song("Ajeeb Dastan Hai Yeh", "Lata Mangeshkar"),
            song("Kuch Aur", "Asha Bhosle"),
        )
        assertEquals(
            "stopword: by",
            "Ajeeb Dastan Hai Yeh",
            VoiceSearchMatcher.findBest("Ajeeb Dastan by Lata", candidates)?.title,
        )
    }

    @Test
    fun `title from movie resolves the song`() {
        val candidates = listOf(
            song("Ajeeb Dastan Hai Yeh", "Lata Mangeshkar"),
            song("Kuch Aur", "Asha Bhosle"),
        )
        assertEquals(
            "from-movie: song title",
            "Ajeeb Dastan Hai Yeh",
            VoiceSearchMatcher.findBest("Ajeeb Dastan Hai Yeh from Dil Apna Aur Preet Parai", candidates)?.title,
        )
    }

    @Test
    fun `cleaned title matches a title with a bracketed suffix`() {
        val candidates = listOf(
            song("Humko Tumse Ho Gaya Hai Pyar (From \"Amar Akbar Anthony\")", "Kishore Kumar"),
        )
        val ranked = VoiceSearchMatcher.rankAll("Humko Tumse Ho Gaya Hai Pyar", candidates)
        assertTrue("cleaned: bracket suffix ignored", ranked.first().score >= 0.95)
    }

    @Test
    fun `transliteration variants match in both directions`() {
        assertNotNull(
            "translit: query Paalanhaare vs title Palan Hare",
            VoiceSearchMatcher.findBest("O Paalanhaare", listOf(song("O Palan Hare", "Lata Mangeshkar"))),
        )
        assertNotNull(
            "translit: query Palan Hare vs title Paalanhaare",
            VoiceSearchMatcher.findBest("O Palan Hare", listOf(song("O Paalanhaare", "Lata Mangeshkar"))),
        )
        assertNotNull(
            "translit: Raabta vs Rabta",
            VoiceSearchMatcher.findBest("Raabta", listOf(song("Rabta", "Arijit Singh"))),
        )
        assertTrue(
            "translit: artist name in query still >= strong threshold",
            VoiceSearchMatcher.rankAll("o palan hare lata mangeshkar", listOf(song("O Paalanhaare", "Lata Mangeshkar")))
                .first().score >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD,
        )
    }

    @Test
    fun `transliteration does not over-match unrelated titles`() {
        assertNull(
            "translit-false: unrelated titles",
            VoiceSearchMatcher.findBest(
                "O Palan Hare",
                listOf(song("Tum Hi Ho", "Arijit Singh"), song("Channa Mereya", "Arijit Singh")),
            ),
        )
    }

    @Test
    fun `cleanTitle strips brackets and dash suffixes`() {
        assertEquals(
            "cleanTitle: bracket suffix",
            "Humko Tumse Ho Gaya Hai Pyar",
            VoiceSearchMatcher.cleanTitle("Humko Tumse Ho Gaya Hai Pyar (From \"Amar Akbar Anthony\")"),
        )
        assertEquals(
            "cleanTitle: dash suffix",
            "Aadmi Musafir Hai",
            VoiceSearchMatcher.cleanTitle("Aadmi Musafir Hai - Happy"),
        )
        assertEquals("cleanTitle: plain title", "Tum Hi Ho", VoiceSearchMatcher.cleanTitle("Tum Hi Ho"))
    }

    @Test
    fun `compactKey ignores word splitting and vowel length`() {
        assertEquals(
            "compactKey: split and vowel-length variants",
            VoiceSearchMatcher.compactKey(listOf("o", "palan", "hare")),
            VoiceSearchMatcher.compactKey(listOf("o", "paalanhaare")),
        )
    }

    @Test
    fun `findBestByName resolves a playlist name ignoring stopwords`() {
        val names = listOf("Road Trip", "Workout", "Bhajans")
        assertEquals("playlist: road trip", "Road Trip", VoiceSearchMatcher.findBestByName("road trip playlist", names) { it })
        assertNull("playlist: no match", VoiceSearchMatcher.findBestByName("Jazz Classics", names) { it })
    }

    @Test
    fun `findBestArtist ignores stopwords in the query`() {
        val candidates = listOf(
            ArtistEntity(id = "lata", name = "Lata Mangeshkar"),
            ArtistEntity(id = "asha", name = "Asha Bhosle"),
        )
        assertEquals(
            "artist: stopword songs",
            "lata",
            VoiceSearchMatcher.findBestArtist("Lata Mangeshkar songs", candidates)?.id,
        )
    }

    @Test
    fun `query made only of stopwords ranks nothing`() {
        assertTrue(
            "stopword-only query",
            VoiceSearchMatcher.rankAll("play songs", listOf(song("Tum Hi Ho", "Arijit Singh"))).isEmpty(),
        )
    }

    @Test
    fun `raw exact match outranks a match that only agrees after stopword removal`() {
        val candidates = listOf(
            song("Sky Full of Stars", "Coldplay"),
            song("A Sky Full of Stars", "Coldplay"),
        )
        val ranked = VoiceSearchMatcher.rankAll("a sky full of stars", candidates)
        assertEquals("exact-beats-loose: winner", "A Sky Full of Stars", ranked.first().song.title)
        assertEquals("exact-beats-loose: raw exact", 1.0, ranked.first().score, 0.0)
        assertEquals("exact-beats-loose: stopword-only capped", 0.99, ranked.last().score, 0.001)
    }

    @Test
    fun `plain title outranks a bracket or dash variant of the same title`() {
        val ranked = VoiceSearchMatcher.rankAll(
            "love story",
            listOf(
                song("Love Story (Taylor's Version)", "Taylor Swift"),
                song("Love Story", "Taylor Swift"),
            ),
        )
        assertEquals("cleaned-penalty: plain first", "Love Story", ranked.first().song.title)
        assertEquals("cleaned-penalty: plain score", 1.0, ranked.first().score, 0.0)
        assertEquals("cleaned-penalty: variant score", 0.99, ranked.last().score, 0.001)

        val dashRanked = VoiceSearchMatcher.rankAll(
            "tum hi ho",
            listOf(
                song("Tum Hi Ho - Remix", "Arijit Singh"),
                song("Tum Hi Ho", "Arijit Singh"),
            ),
        )
        assertEquals("cleaned-penalty: dash plain first", "Tum Hi Ho", dashRanked.first().song.title)
    }

    @Test
    fun `from-split query variant cannot tie the full query match`() {
        val ranked = VoiceSearchMatcher.rankAll(
            "Hello From The Other Side",
            listOf(
                song("Hello", "Adele"),
                song("Hello From The Other Side", "Ed Sheeran"),
            ),
        )
        assertEquals("from-cap: full-query match wins", "Hello From The Other Side", ranked.first().song.title)
        assertEquals("from-cap: full-query score", 1.0, ranked.first().score, 0.0)
        assertTrue("from-cap: bare title capped at 0.95", ranked.last().score <= 0.95)
    }

    @Test
    fun `compact match allows one edit but not prefix-sharing titles`() {
        assertEquals(
            "compact: equal keys",
            0.95,
            VoiceSearchMatcher.compactScore(listOf("o", "palan", "hare"), listOf("o", "paalanhaare")),
            0.0,
        )
        assertEquals(
            "compact: one edit",
            0.9,
            VoiceSearchMatcher.compactScore(listOf("opalanhar"), listOf("opalanhare")),
            0.0,
        )
        assertEquals(
            "compact: dilchahtahai vs dilchahtatha",
            0.0,
            VoiceSearchMatcher.compactScore(listOf("dilchahtahai"), listOf("dilchahtatha")),
            0.0,
        )
        assertEquals(
            "compact: kuchkuchhota vs kuchkuchhotahai",
            0.0,
            VoiceSearchMatcher.compactScore(listOf("kuchkuchhota"), listOf("kuchkuchhotahai")),
            0.0,
        )
    }

    @Test
    fun `compactKey collapses letters but keeps digits`() {
        assertEquals("compactKey: digits kept", "part11", VoiceSearchMatcher.compactKey(listOf("part", "11")))
        assertEquals("compactKey: single digit", "part1", VoiceSearchMatcher.compactKey(listOf("part", "1")))
    }

    @Test
    fun `compactScore rejects titles whose digits differ`() {
        assertEquals(
            "compact-digits: Part 11 vs Part 1",
            0.0,
            VoiceSearchMatcher.compactScore(listOf("part", "11"), listOf("part", "1")),
            0.0,
        )
        assertEquals(
            "compact-digits: Volume 2 vs Volume 3",
            0.0,
            VoiceSearchMatcher.compactScore(listOf("volume", "2"), listOf("volume", "3")),
            0.0,
        )
    }
}