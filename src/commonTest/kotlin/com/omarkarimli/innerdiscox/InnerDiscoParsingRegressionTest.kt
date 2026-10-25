package com.omarkarimli.innerdiscox

import com.omarkarimli.innerdiscox.models.WatchEndpoint
import com.omarkarimli.innerdiscox.models.response.AccountMenuResponse
import com.omarkarimli.innerdiscox.models.response.AccountMenuResponse.Action.OpenPopupAction.Popup.MultiPageMenuRenderer.Header.ActiveAccountHeaderRenderer
import com.omarkarimli.innerdiscox.models.response.PlayerResponse
import com.omarkarimli.innerdiscox.utils.DiscoUrlParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerDiscoParsingRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun musicDiscoPlaylistUrlsAreNotAlwaysAlbums() {
        val parsed = DiscoUrlParser.parse("https://music.youtube.com/playlist?list=PLabc123")

        assertTrue(parsed is DiscoUrlParser.ParsedUrl.Playlist)
        assertEquals("PLabc123", parsed.id)
    }

    @Test
    fun musicDiscoAlbumUrlsUseAlbumIds() {
        val playlistAlbum = DiscoUrlParser.parse("https://music.youtube.com/playlist?list=OLAK5uy_test")
        val browseAlbum = DiscoUrlParser.parse("https://music.youtube.com/browse/MPREtest")

        assertTrue(playlistAlbum is DiscoUrlParser.ParsedUrl.Album)
        assertEquals("OLAK5uy_test", playlistAlbum.id)
        assertTrue(browseAlbum is DiscoUrlParser.ParsedUrl.Album)
        assertEquals("MPREtest", browseAlbum.id)
        assertEquals(
            DiscoUrlParser.ParsedUrl.Album("MPREtest"),
            DiscoUrlParser.parse("https://www.music.youtube.com/browse/MPREtest"),
        )
    }

    @Test
    fun discoUrlsRequireAnExactApprovedHostAndShape() {
        val valid = DiscoUrlParser.parse("youtube.com/watch?v=abcdefghijk")

        assertEquals(DiscoUrlParser.ParsedUrl.Video("abcdefghijk"), valid)
        assertEquals(
            DiscoUrlParser.ParsedUrl.Video("abcdefghijk"),
            DiscoUrlParser.parse("http://youtu.be/abcdefghijk"),
        )
        assertTrue(DiscoUrlParser.parse("https://www.youtube.com.evil/watch?v=abcdefghijk") == null)
        assertTrue(DiscoUrlParser.parse("https://www.youtube.com/notv?v=abcdefghijk") == null)
        assertTrue(DiscoUrlParser.parse("watch this https://www.youtube.com/watch?v=abcdefghijk") == null)
        assertTrue(DiscoUrlParser.parse("https://youtu.be.evil/abcdefghijk") == null)
    }

    @Test
    fun mobileWatchUrlsWorkAcrossParserHelpers() {
        val url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

        assertEquals(DiscoUrlParser.ParsedUrl.Video("dQw4w9WgXcQ"), DiscoUrlParser.parse(url))
        assertTrue(DiscoUrlParser.isDiscoUrl(url))
        assertEquals("dQw4w9WgXcQ", DiscoUrlParser.extractVideoId(url))
        assertEquals(WatchEndpoint("dQw4w9WgXcQ"), DiscoUrlParser.createWatchEndpoint(url))
        for (invalid in listOf(
            "https://m.youtube.com.evil/watch?v=dQw4w9WgXcQ",
            "https://evil-m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com@evil.test/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/notv?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=short",
        )) {
            assertNull(DiscoUrlParser.parse(invalid))
            assertNull(DiscoUrlParser.createWatchEndpoint(invalid))
        }
    }

    @Test
    fun accountMenuUnknownAndMissingVariantsDecodeSafely() {
        val response =
            json.decodeFromString<AccountMenuResponse>(
                """
                {
                  "actions": [
                    {"unknownAction": {"value": 1}},
                    {"openPopupAction": {"popup": {"multiPageMenuRenderer": {"header": {}}}}},
                    {"openPopupAction": {"popup": {"popupContent": {"multiPageMenuRenderer": {"header": {}}}}}}
                  ],
                  "unknownRoot": true
                }
                """.trimIndent(),
            )

        assertEquals(3, response.actions.size)
        val header =
            response.actions[1]
                .openPopupAction
                ?.popup
                ?.multiPageMenuRenderer
                ?.header
        assertNotNull(header).also { assertNull(it.activeAccountHeaderRenderer) }
        assertNotNull(
            response.actions[2]
                .openPopupAction
                ?.popup
                ?.popupContent
                ?.multiPageMenuRenderer,
        )
        val missingAccount = ActiveAccountHeaderRenderer()
        assertEquals(
            "Unknown",
            missingAccount.toAccountInfo().name,
        )
    }

    @Test
    fun partialPlayerFormatsDoNotDropWholeResponse() {
        val response =
            json.decodeFromString<PlayerResponse>(
                """
                {
                  "playabilityStatus": { "status": "OK" },
                  "streamingData": {
                    "adaptiveFormats": [
                      { "url": "https://example.test/videoplayback" }
                    ]
                  }
                }
                """.trimIndent(),
            )

        val format = assertNotNull(response.streamingData?.adaptiveFormats?.singleOrNull())
        assertEquals(-1, format.itag)
        assertEquals("", format.mimeType)
        assertEquals(0, format.bitrate)
        assertEquals("https://example.test/videoplayback", format.url)
    }
}
