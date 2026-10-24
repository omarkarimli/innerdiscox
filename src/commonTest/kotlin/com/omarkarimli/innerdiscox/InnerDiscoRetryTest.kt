package com.omarkarimli.innerdiscox

import com.omarkarimli.innerdiscox.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InnerDiscoRetryTest {
    @Test
    fun retriesTransientHttpStatuses() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests < 3) {
                            respond("{}", HttpStatusCode.ServiceUnavailable, JSON_HEADERS)
                        } else {
                            respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                        }
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val response = InnerDisco(client, retryDelay = {}).browse(YouTubeClient.WEB_REMIX, browseId = "test")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(3, requests)
            client.close()
        }

    @Test
    fun doesNotRetryPermanentHttpStatuses() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val error =
                assertFailsWith<InnerDiscoHttpException> {
                    InnerDisco(client, retryDelay = {}).browse(YouTubeClient.WEB_REMIX, browseId = "test")
                }

            assertEquals(HttpStatusCode.Unauthorized, error.status)
            assertEquals(1, requests)
            client.close()
        }

    @Test
    fun rateLimitIsRetriedOnlyWithShortRetryAfter() =
        runBlocking {
            var requests = 0
            val delays = mutableListOf<kotlin.time.Duration>()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        when (requests) {
                            1 -> respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
                            else -> respond("{}", HttpStatusCode.TooManyRequests, JSON_HEADERS)
                        }
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFailsWith<InnerDiscoHttpException> {
                InnerDisco(client, retryDelay = { delays += it }).browse(YouTubeClient.WEB_REMIX, browseId = "test")
            }

            assertEquals(2, requests)
            assertEquals(listOf(kotlin.time.Duration.parse("2s")), delays)
            client.close()
        }

    @Test
    fun neverRetriesMutations() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.ServiceUnavailable, JSON_HEADERS)
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            val response = InnerDisco(client, retryDelay = {}).likeVideo(YouTubeClient.WEB_REMIX, "video")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(1, requests)
            client.close()
        }

    @Test
    fun neverRetriesMutationTransportFailures() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("connection closed after dispatch")
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFailsWith<IllegalStateException> {
                InnerDisco(client, retryDelay = {}).likeVideo(YouTubeClient.WEB_REMIX, "video")
            }
            assertEquals(1, requests)
            client.close()
        }

    @Test
    fun doesNotRetryDeterministicFailures() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        throw IllegalArgumentException("invalid request")
                    },
                ) { install(ContentNegotiation) { json() } }
            assertFailsWith<IllegalArgumentException> { InnerDisco(client, retryDelay = {}).browse(YouTubeClient.WEB) }
            assertEquals(1, requests)
            client.close()
        }

    @Test
    fun retriesTransientTransportFailures() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            throw io.ktor.utils.io.errors
                                .IOException("connection interrupted")
                        }
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) { install(ContentNegotiation) { json() } }
            assertEquals(HttpStatusCode.OK, InnerDisco(client, retryDelay = {}).browse(YouTubeClient.WEB).status)
            assertEquals(2, requests)
            client.close()
        }

    @Test
    fun permanentFailureAfterRetryIsNotLoggedAsSuccess() =
        runBlocking {
            var requests = 0
            val messages = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", if (requests == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Forbidden, JSON_HEADERS)
                    },
                ) {
                    expectSuccess = true
                    install(ContentNegotiation) { json() }
                }
            val innerDisco = InnerDisco(client, retryDelay = {}, logger = InnerDiscoLogger { messages += it.message })
            assertFailsWith<InnerDiscoHttpException> { innerDisco.browse(YouTubeClient.WEB) }
            assertEquals(2, requests)
            assertTrue(messages.none { "succeeded" in it })
            client.close()
        }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
