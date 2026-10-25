package com.omarkarimli.innerdiscox.extraction

public interface DiscoConfigParser {
    /** Sends login cookies only to the validated Disco watch/embed page. */
    public suspend fun fetchConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig

    public suspend fun fetchEmbeddedConfig(
        videoId: String,
        useLoginCookies: Boolean = false,
    ): PlayerConfig = fetchConfig(videoId, useLoginCookies)
}
