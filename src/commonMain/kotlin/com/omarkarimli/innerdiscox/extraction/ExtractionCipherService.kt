package com.omarkarimli.innerdiscox.extraction

import com.omarkarimli.innerdiscox.models.response.PlayerResponse.StreamingData.Format

/**
 * Player cipher operations used by [InnerDiscoExtractor].
 *
 * [com.omarkarimli.innerdiscox.cipher.YouTubeCipherService] is the in-process implementation. Hosts can
 * supply their own implementation, for example one that runs the JavaScript solver in a separate
 * process so a native engine crash cannot take down playback.
 */
interface ExtractionCipherService {
    suspend fun initialize()

    suspend fun preloadPlayerCode(playerUrl: String)

    suspend fun prewarmEjs()

    suspend fun processFormats(
        playerUrl: String,
        formats: List<Format>,
    ): List<Format>
}
