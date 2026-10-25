package com.omarkarimli.innerdiscox.cipher

import kotlin.text.Charsets

private object DiscoEjsResourceAnchor

internal actual fun readDiscoEjsSolverScript(fileName: String): String {
    val path = "disco_ejs/$fileName"
    val stream =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
            ?: DiscoEjsResourceAnchor.javaClass.getResourceAsStream("/$path")
            ?: DiscoEjsResourceAnchor.javaClass.getResourceAsStream(path)
    requireNotNull(stream) { "Missing classpath resource: $path" }
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
