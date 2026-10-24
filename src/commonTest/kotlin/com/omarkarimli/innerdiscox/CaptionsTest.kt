package com.omarkarimli.innerdiscox

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptionsTest {
    @Test
    fun captionFetchAcceptsOnlyBoundedApprovedEndpoints() {
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("www.youtube.com", request.url.host)
                    respond("caption", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "7"))
                }
            val innerDisco = InnerDisco(HttpClient(engine))

            assertEquals("caption", innerDisco.fetchCaptionText("https://www.youtube.com/api/timedtext?v=abcdefghijk"))
            assertFailsWith<IllegalArgumentException> {
                innerDisco.fetchCaptionText("https://example.com/api/timedtext?v=abcdefghijk")
            }
            assertFailsWith<IllegalArgumentException> {
                innerDisco.fetchCaptionText("https://www.youtube.com:8443/api/timedtext?v=abcdefghijk")
            }
        }
    }

    @Test
    fun captionFetchRejectsOversizedBody() {
        runBlocking {
            val innerDisco =
                InnerDisco(
                    HttpClient(
                        MockEngine {
                            respond("x", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (5 * 1024 * 1024).toString()))
                        },
                    ),
                )

            assertFailsWith<IllegalStateException> {
                innerDisco.fetchCaptionText("https://www.youtube.com/api/timedtext?v=abcdefghijk")
            }
        }
    }

    @Test
    fun captionFetchDoesNotFollowRedirects() {
        runBlocking {
            var requests = 0
            val innerDisco =
                InnerDisco(
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://example.com/api/timedtext"),
                            )
                        },
                    ),
                )

            assertFailsWith<InnerDiscoHttpException> {
                innerDisco.fetchCaptionText("https://www.youtube.com/api/timedtext?v=abcdefghijk")
            }
            assertEquals(1, requests)
        }
    }
}
