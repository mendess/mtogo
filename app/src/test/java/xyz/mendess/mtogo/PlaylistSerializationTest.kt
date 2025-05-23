package xyz.mendess.mtogo

import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.mendess.mtogo.viewmodels.Playlist

private const val PLAYLIST = """[
    {
        "name": "Moonlight Sonata",
        "link": "https://youtu.be/4Tr0otuiQuU",
        "time": 900,
        "categories": [],
        "genre": "classical"
    },
    {
        "name": "sunday school",
        "link": "https://youtu.be/rTfa-9aCTYg",
        "time": 188,
        "categories": [
            "chill"
        ],
        "genre": "synthwave"
    },
    {
        "name": "Joji - SLOW DANCING IN THE DARK",
        "link": "https://youtu.be/K3Qzzggn--s",
        "time": 217,
        "categories": [
            "chill"
        ],
        "artist": "joji",
        "genre": "r&b"
    },
    {
        "name": "Für Elise",
        "link": "https://youtu.be/wfF0zHeU3Zs",
        "time": 175,
        "categories": [],
        "genre": "classical"
    },
    {
        "name": "Highly Suspect - \"BATH SALTS\"",
        "link": "https://youtu.be/VoA9tLkrgHY",
        "time": 171,
        "categories": [],
        "artist": "highly-suspect",
        "genre": "rock",
        "recommended_by": "mighty"
    },
    {
        "name": "Slow J - Ultimamente",
        "link": "https://youtu.be/wzU-MCa3rBQ",
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
        "link": "https://youtu.be/ZYg-HA6T4no",
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
                !s.id.get().startsWith("https://youtu.be/"),
            )
        }

        TestCase.assertEquals(JSON.encodeToString(playlist), PLAYLIST)
    }
}