package xyz.mendess.mtogo

import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.mendess.mtogo.viewmodels.Playlist

private const val PLAYLIST = """[
    {
        "name": "Moonlight Sonata",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/5R6FH6kr",
        "time": 900,
        "categories": [],
        "genre": "classical"
    },
    {
        "name": "sunday school",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/nFHz1gAF",
        "time": 188,
        "categories": [
            "chill"
        ],
        "genre": "synthwave"
    },
    {
        "name": "Joji - SLOW DANCING IN THE DARK",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/ZTpunltJ",
        "time": 217,
        "categories": [
            "chill"
        ],
        "artist": "joji",
        "genre": "r&b"
    },
    {
        "name": "Für Elise",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/u325KOYg",
        "time": 175,
        "categories": [],
        "genre": "classical"
    },
    {
        "name": "Highly Suspect - \"BATH SALTS\"",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/1tUfxl9q",
        "time": 171,
        "categories": [],
        "artist": "highly-suspect",
        "genre": "rock",
        "recommended_by": "mighty"
    },
    {
        "name": "Slow J - Ultimamente",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/MoFlkE3Z",
        "time": 222,
        "categories": [
            "sad"
        ],
        "artist": "slow-j",
        "genre": "rap",
        "liked_by": [
            "mariii"
        ]
    },
    {
        "name": "Valeria Castro - lo que siento",
        "link": "https://blind-eternities.mendess.xyz/playlist/song/audio/IXFLH8bD",
        "time": 188,
        "categories": [
            "upbeat"
        ],
        "artist": "valeria-castro",
        "language": "spanish"
    }
]"""

private val JSON = Json { prettyPrint = true }

class PlaylistSerializationTest {
    @Test
    fun `serialization and deserialization works`() {
        val playlist = JSON.decodeFromString<List<Playlist.Song>>(PLAYLIST)

        for (s in playlist) {
            TestCase.assertTrue(
                "${s.id} can't be the entire link",
                !s.id.get().startsWith("https://blind-eternities.mendess.xyz/playlist/song/audio"),
            )
        }

        TestCase.assertEquals(
            "encoding didn't match decoding",
            JSON.encodeToString(playlist),
            PLAYLIST
        )
    }
}