package com.omarkarimli.innerdiscox

enum class InnerDiscoLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class InnerDiscoLogEvent(
    val level: InnerDiscoLogLevel,
    val tag: String,
    val message: String,
    val mediaId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

fun interface InnerDiscoLogger {
    fun log(event: InnerDiscoLogEvent)

    companion object {
        val NONE = InnerDiscoLogger {}
    }
}

internal fun InnerDiscoLogger.d(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerDiscoLogEvent(InnerDiscoLogLevel.DEBUG, tag, message, mediaId, details))

internal fun InnerDiscoLogger.i(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerDiscoLogEvent(InnerDiscoLogLevel.INFO, tag, message, mediaId, details))

internal fun InnerDiscoLogger.w(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerDiscoLogEvent(InnerDiscoLogLevel.WARN, tag, message, mediaId, details))

internal fun InnerDiscoLogger.e(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerDiscoLogEvent(InnerDiscoLogLevel.ERROR, tag, message, mediaId, details))
