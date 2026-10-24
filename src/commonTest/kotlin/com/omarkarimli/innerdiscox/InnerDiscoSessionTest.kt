package com.omarkarimli.innerdiscox

import com.omarkarimli.innerdiscox.models.YouTubeClient
import com.omarkarimli.innerdiscox.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerDiscoSessionTest {
    @Test
    fun statisticsRedirectsNeverForwardCredentials() =
        runBlocking {
            for (location in listOf(
                "https://example.test/collect",
                "https://s.youtube.com/unapproved",
                "https://s.youtube.com/api/stats/watchtime",
            )) {
                val engine =
                    MockEngine {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    }
                val client =
                    HttpClient(engine) {
                        expectSuccess = true
                        followRedirects = true
                    }
                val innerDisco = InnerDisco(client).also { it.cookie = "SAPISID=secret" }
                val response = innerDisco.registerPlayback(YouTubeClient.WEB, "https://s.youtube.com/api/stats/playback", "cpn")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals(1, engine.requestHistory.size)
                assertEquals("SAPISID=secret", engine.requestHistory.single().headers[HttpHeaders.Cookie])
                innerDisco.close()
                client.close()
            }
        }

    @Test
    fun mediaLengthIgnoresErrorAndRedirectBodies() =
        runBlocking {
            for (status in listOf(
                HttpStatusCode.Forbidden,
                HttpStatusCode.NotFound,
                HttpStatusCode.MethodNotAllowed,
                HttpStatusCode.Found,
            )) {
                val engine =
                    MockEngine {
                        respond(
                            "",
                            status,
                            headersOf(
                                HttpHeaders.ContentLength to listOf("123"),
                                HttpHeaders.Location to listOf("https://example.test/media"),
                            ),
                        )
                    }
                val client = HttpClient(engine) { followRedirects = true }
                val innerDisco = InnerDisco(client)
                assertNull(innerDisco.mediaContentLength("https://rr1.googlevideo.com/videoplayback", emptyMap()))
                assertEquals(1, engine.requestHistory.size)
                innerDisco.close()
                client.close()
            }
        }

    @Test
    fun visitorFetchAfterCloseAndDuringCloseAlwaysCompletes() =
        runBlocking {
            repeat(20) {
                val innerDisco =
                    InnerDisco(
                        HttpClient(
                            MockEngine {
                                CompletableDeferred<Unit>().await()
                                respondOk()
                            },
                        ),
                    )
                val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { innerDisco.fetchFreshVisitorData() }
                innerDisco.close()
                withTimeout(2_000) {
                    assertFailsWith<CancellationException> { pending.await() }
                    assertFailsWith<CancellationException> { innerDisco.fetchFreshVisitorData() }
                }
                innerDisco.httpClient.close()
            }
        }

    @Test
    fun stalledVisitorProducerTimesOutAndAllowsFreshFlight() =
        runBlocking {
            var attempts = 0
            val innerDisco =
                InnerDisco(
                    HttpClient(
                        MockEngine {
                            attempts++
                            if (attempts == 1) CompletableDeferred<Unit>().await()
                            respond(serviceWorkerResponse("recovered"), HttpStatusCode.OK)
                        },
                    ),
                )
            try {
                withTimeout(20_000) {
                    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { innerDisco.fetchFreshVisitorData() }
                    assertEquals("recovered", innerDisco.fetchFreshVisitorData())
                }
                assertEquals(2, attempts)
            } finally {
                innerDisco.close()
                innerDisco.httpClient.close()
            }
        }

    @Test
    fun visitorFallbackHasOneServiceWorkerAttemptAndTwoHomepageAttempts() =
        runBlocking {
            val engine = MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
            val delays = mutableListOf<kotlin.time.Duration>()
            val innerDisco = InnerDisco(HttpClient(engine), retryDelay = { delays += it })
            assertNull(innerDisco.fetchFreshVisitorData())
            assertEquals(listOf("www.youtube.com", "music.youtube.com", "music.youtube.com"), engine.requestHistory.map { it.url.host })
            assertEquals(listOf(kotlin.time.Duration.parse("500ms")), delays)
            innerDisco.close()
            innerDisco.httpClient.close()
        }

    @Test
    fun invalidationCancelsOnlyRequestAndPreservesHostAndSibling() =
        runBlocking {
            for (close in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val engine =
                    MockEngine {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        respondOk()
                    }
                val innerDisco = clientWithContentNegotiation(engine)
                val host =
                    launch {
                        val sibling = launch { CompletableDeferred<Unit>().await() }
                        try {
                            assertFailsWith<CancellationException> { innerDisco.likeVideo(YouTubeClient.WEB, "video") }
                            assertTrue(isActive)
                            assertTrue(sibling.isActive)
                        } finally {
                            sibling.cancelAndJoin()
                        }
                    }
                started.await()
                if (close) innerDisco.close() else innerDisco.cookie = "SAPISID=new"
                withTimeout(2_000) { host.join() }
                assertFalse(host.isCancelled)
                innerDisco.close()
                innerDisco.httpClient.close()
            }
        }

    @Test
    fun authenticatedReadsCannotRetryAfterLogout() =
        runBlocking {
            for (search in listOf(false, true)) {
                val engine = MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) }
                val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
                lateinit var innerDisco: InnerDisco
                innerDisco = InnerDisco(client, retryDelay = { innerDisco.cookie = null })
                innerDisco.cookie = "SAPISID=old"
                assertFailsWith<CancellationException> {
                    if (search) {
                        innerDisco.search(YouTubeClient.WEB, "query")
                    } else {
                        innerDisco.browse(YouTubeClient.WEB, "library", setLogin = true)
                    }
                }
                assertEquals(1, engine.requestHistory.size)
                innerDisco.close()
                client.close()
            }
        }

    @Test
    fun accountSwitchCancelsEveryAccountRead() =
        runBlocking {
            val reads: List<suspend (InnerDisco) -> Unit> =
                listOf(
                    { it.accountMenu(YouTubeClient.WEB) },
                    { it.accountsList(YouTubeClient.WEB) },
                    { it.player(YouTubeClient.WEB, "video") },
                    { it.next(YouTubeClient.WEB, "video") },
                    { it.getQueue(YouTubeClient.WEB, listOf("video"), null) },
                    { it.getSearchSuggestions(YouTubeClient.WEB, "query", setLogin = true) },
                )
            for (read in reads) {
                val started = CompletableDeferred<Unit>()
                val engine =
                    MockEngine {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        respondOk()
                    }
                val innerDisco =
                    clientWithContentNegotiation(engine).also {
                        it.cookie = "SAPISID=old"
                        it.useLoginForBrowse = true
                    }
                val result = async { read(innerDisco) }
                started.await()
                innerDisco.cookie = "SAPISID=new"
                withTimeout(2_000) { assertFailsWith<CancellationException> { result.await() } }
                innerDisco.close()
                innerDisco.httpClient.close()
            }
        }

    @Test
    fun initialVisitorPublicationPreservesConcurrentPlayerAndMutation() =
        runBlocking {
            val started = kotlinx.coroutines.channels.Channel<Unit>(2)
            val release = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/sw.js_data") {
                        respond(serviceWorkerResponse("visitor"), HttpStatusCode.OK)
                    } else {
                        started.send(Unit)
                        release.await()
                        respondOk()
                    }
                }
            val innerDisco = clientWithContentNegotiation(engine)
            val player = async { innerDisco.player(YouTubeClient.WEB, "video") }
            val mutation = async { innerDisco.likeVideo(YouTubeClient.WEB, "video") }
            repeat(2) { started.receive() }
            assertEquals("visitor", innerDisco.fetchFreshVisitorData())
            release.complete(Unit)
            assertEquals(HttpStatusCode.OK, player.await().status)
            assertEquals(HttpStatusCode.OK, mutation.await().status)
            innerDisco.close()
            innerDisco.httpClient.close()
        }

    @Test
    fun visitorSnapshotAcceptsOnlyTheInitialPublication() {
        val innerDisco = InnerDisco(HttpClient(MockEngine { respondOk() }))
        val signedOut = innerDisco.sessionSnapshot()
        innerDisco.visitorData = "first"
        val published = innerDisco.sessionSnapshot()
        assertEquals(published, innerDisco.sessionSnapshotWithVisitorData(signedOut, "first"))

        innerDisco.visitorData = "second"
        assertNull(innerDisco.sessionSnapshotWithVisitorData(published, "second"))
        assertNull(innerDisco.sessionSnapshotWithVisitorData(signedOut, "second"))
    }

    @Test
    fun visitorReplacementAndLocaleChangesStillInvalidateRequests() =
        runBlocking {
            for (changeLocale in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val engine =
                    MockEngine {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        respondOk()
                    }
                val innerDisco = clientWithContentNegotiation(engine).also { it.visitorData = "existing" }
                val result = async { innerDisco.likeVideo(YouTubeClient.WEB, "video") }
                started.await()
                if (changeLocale) innerDisco.locale = YouTubeLocale("PL", "pl") else innerDisco.visitorData = "replacement"
                assertFailsWith<CancellationException> { result.await() }
                innerDisco.close()
                innerDisco.httpClient.close()
            }
        }

    @Test
    fun cookieHeaderPrefixIsNormalizedBeforeSending() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "Cookie: SAPISID=secret" }

            innerDisco.registerPlaybackWithSession(
                client = YouTubeClient.WEB,
                url = "https://s.youtube.com/api/stats/playback",
                cpn = "cpn",
                playlistId = null,
                requestSession = innerDisco.sessionSnapshot(),
            )

            assertEquals("SAPISID=secret", engine.requestHistory.single().headers[HttpHeaders.Cookie])
        }

    @Test
    fun cookieLineBreaksAreRemovedAndOtherControlCharactersAreRejected() {
        val lineWrapped = InnerDisco(HttpClient(MockEngine { respondOk() }))
        lineWrapped.cookie = "SAPISID=secret\r\n\t; PREF=f6=400"

        assertEquals("SAPISID=secret; PREF=f6=400", lineWrapped.cookie)

        assertFailsWith<IllegalArgumentException> { lineWrapped.cookie = "SAPISID=secret\u0000" }
    }

    @Test
    fun lineWrappedVisitorDataIsJoinedBeforePlayerRequest() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = null,
                        visitorData = " Cgt-visitor_\r\n part%3D%3D\n",
                        dataSyncId = null,
                        authUser = "0",
                        useLoginForBrowse = false,
                    )
                }

            innerDisco.player(YouTubeClient.VISIONOS, videoId = "video-id")

            assertEquals("Cgt-visitor_part%3D%3D", engine.requestHistory.single().headers["X-Goog-Visitor-Id"])
        }

    @Test
    fun visitorDataThatCannotBeSentAsHeaderIsDropped() {
        val innerDisco = InnerDisco(HttpClient(MockEngine { respondOk() }))
        innerDisco.visitorData = "Cgt\u0000visitor"
        assertNull(innerDisco.visitorData)

        innerDisco.visitorData = "Cgtévisitor"
        assertNull(innerDisco.visitorData)
    }

    @Test
    fun sessionSnapshotStringDoesNotExposeCredentials() {
        val snapshot =
            InnerDisco.SessionSnapshot(
                visitorData = "visitor-secret",
                dataSyncId = "sync-secret",
                authUser = "auth-secret",
                cookie = "SAPISID=cookie-secret",
                sapisid = "sapisid-secret",
            )

        val rendered = snapshot.toString()

        assertFalse(rendered.contains("visitor-secret"))
        assertFalse(rendered.contains("sync-secret"))
        assertFalse(rendered.contains("auth-secret"))
        assertFalse(rendered.contains("cookie-secret"))
        assertFalse(rendered.contains("sapisid-secret"))
    }

    @Test
    fun isolatedSessionsCopyAccountOnlyWhenRequested() {
        val innerDisco = InnerDisco(HttpClient(MockEngine { respondOk() }))
        innerDisco.replaceSession(
            cookie = "SAPISID=secret",
            visitorData = "visitor",
            dataSyncId = "account",
            authUser = "3",
            useLoginForBrowse = true,
        )
        innerDisco.regionOverrideActive = true

        val anonymous = innerDisco.createIsolatedSession(includeAccount = false).sessionSnapshot()
        val authenticated = innerDisco.createIsolatedSession(includeAccount = true).sessionSnapshot()

        assertNull(anonymous.cookie)
        assertEquals("visitor", anonymous.visitorData)
        assertNull(anonymous.dataSyncId)
        assertFalse(anonymous.useLoginForBrowse)
        assertEquals(innerDisco.locale, anonymous.locale)
        assertTrue(anonymous.regionOverrideActive)
        assertEquals("SAPISID=secret", authenticated.cookie)
        assertEquals("visitor", authenticated.visitorData)
        assertEquals("account", authenticated.dataSyncId)
        assertEquals("3", authenticated.authUser)
        assertTrue(authenticated.useLoginForBrowse)
    }

    @Test
    fun concurrentMissingVisitorDataRequestsShareOneFetch() =
        runBlocking {
            val engine = MockEngine { respond(serviceWorkerResponse("visitor"), HttpStatusCode.OK) }
            val innerDisco = InnerDisco(HttpClient(engine))
            val requestSession = innerDisco.sessionSnapshot()

            val visitors = List(8) { async { innerDisco.fetchFreshVisitorData(requestSession) } }.awaitAll()

            assertTrue(visitors.all { it == "visitor" })
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun publicFreshVisitorDataRequestReplacesExistingValue() =
        runBlocking {
            val engine = MockEngine { respond(serviceWorkerResponse("new-visitor"), HttpStatusCode.OK) }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.visitorData = "old-visitor" }

            assertEquals("new-visitor", innerDisco.fetchFreshVisitorData())
            assertEquals("new-visitor", innerDisco.visitorData)
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun concurrentMissingVisitorDataRequestsShareFailedFetch() =
        runBlocking {
            val engine =
                MockEngine {
                    delay(50)
                    respond("no visitor data", HttpStatusCode.OK)
                }
            val innerDisco = InnerDisco(HttpClient(engine))
            val requestSession = innerDisco.sessionSnapshot()

            val visitors = List(8) { async { innerDisco.fetchFreshVisitorData(requestSession) } }.awaitAll()

            assertTrue(visitors.all { it == null })
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun serviceWorkerFailureFallsBackToHomepageVisitorData() =
        runBlocking {
            var serviceWorkerRequests = 0
            val engine =
                MockEngine { request ->
                    if (request.url.host == "www.youtube.com") {
                        serviceWorkerRequests++
                        respond("temporary failure", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("""{"VISITOR_DATA":"homepage-visitor"}""", HttpStatusCode.OK)
                    }
                }
            val innerDisco = InnerDisco(HttpClient(engine), retryDelay = {})

            assertEquals("homepage-visitor", innerDisco.fetchFreshVisitorData())
            assertEquals(1, serviceWorkerRequests)
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun cancellingFirstVisitorDataCallerDoesNotCancelSharedFetch() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respond(serviceWorkerResponse("shared-visitor"), HttpStatusCode.OK)
                }
            val innerDisco = InnerDisco(HttpClient(engine))
            val requestSession = innerDisco.sessionSnapshot()

            val firstCaller = async { innerDisco.fetchFreshVisitorData(requestSession) }
            requestStarted.await()
            val survivingCaller = async { innerDisco.fetchFreshVisitorData(requestSession) }
            firstCaller.cancelAndJoin()
            releaseResponse.complete(Unit)

            assertEquals("shared-visitor", survivingCaller.await())
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun cancellingVisitorDataWaiterDoesNotCancelSharedFetch() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respond(serviceWorkerResponse("shared-visitor"), HttpStatusCode.OK)
                }
            val innerDisco = InnerDisco(HttpClient(engine))
            val requestSession = innerDisco.sessionSnapshot()

            val survivingCaller = async { innerDisco.fetchFreshVisitorData(requestSession) }
            requestStarted.await()
            val cancelledWaiter = async { innerDisco.fetchFreshVisitorData(requestSession) }
            cancelledWaiter.cancelAndJoin()
            releaseResponse.complete(Unit)

            assertEquals("shared-visitor", survivingCaller.await())
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun sessionChangeCancelsPinnedPlaybackRegistration() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respond("", HttpStatusCode.OK)
                }
            val innerDisco = InnerDisco(HttpClient(engine))
            innerDisco.cookie = "SAPISID=old-session"
            val requestSession = innerDisco.sessionSnapshot()

            val registration =
                async {
                    innerDisco.registerPlaybackWithSession(
                        YouTubeClient.WEB_REMIX,
                        "https://s.youtube.com/api/stats/playback",
                        "cpn",
                        null,
                        requestSession,
                    )
                }
            requestStarted.await()
            innerDisco.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { registration.await() }
            Unit
        }

    @Test
    fun sessionChangeCancelsAccountMutation() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respondOk()
                }
            val innerDisco = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=old-session" }

            val mutation = async { innerDisco.likeVideo(YouTubeClient.WEB_REMIX, "video-id") }
            requestStarted.await()
            innerDisco.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { mutation.await() }
            Unit
        }

    @Test
    fun staleSessionCannotStartPlaylistMutation() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=old-session" }
            val oldSession = innerDisco.sessionSnapshot()
            innerDisco.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> {
                innerDisco.unlikePlaylist(YouTubeClient.WEB_REMIX, "playlist-id", oldSession)
            }
            assertFailsWith<CancellationException> {
                innerDisco.deletePlaylist(YouTubeClient.WEB_REMIX, "playlist-id", oldSession)
            }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun sessionChangeCancelsFeedback() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respondOk()
                }
            val innerDisco = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=old-session" }

            val mutation = async { innerDisco.feedback(YouTubeClient.WEB_REMIX, listOf("feedback-token")) }
            requestStarted.await()
            innerDisco.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { mutation.await() }
            Unit
        }

    @Test
    fun sessionChangeCancelsUploadFinalize() =
        runBlocking {
            val finalizeStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.headers["X-Goog-Upload-Command"] == "start") {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/upload/session"),
                        )
                    } else {
                        finalizeStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        respondOk()
                    }
                }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=old-session" }

            val upload = async { innerDisco.uploadSong("track.mp3", byteArrayOf(1, 2, 3)) }
            finalizeStarted.await()
            innerDisco.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { upload.await() }
            Unit
        }

    @Test
    fun playbackRegistrationRejectsUntrustedStatsHostBeforeSendingCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            listOf(
                "https://example.com/api/stats/playback",
                "https://s.youtube.com:8443/api/stats/playback",
                "https://s.youtube.com/api/stats/playback/extra",
            ).forEach { url ->
                assertFailsWith<IllegalArgumentException> {
                    innerDisco.registerPlaybackWithSession(
                        YouTubeClient.WEB_REMIX,
                        url,
                        "cpn",
                        null,
                        innerDisco.sessionSnapshot(),
                    )
                }
            }

            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun anonymousStatsRegistrationDoesNotSendAccountCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            innerDisco.registerPlaybackWithSession(
                YouTubeClient.ANDROID,
                "https://s.youtube.com/api/stats/playback",
                "cpn",
                null,
                innerDisco.sessionSnapshot(),
            )

            val request = engine.requestHistory.single()
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Goog-AuthUser"])
        }

    @Test
    fun authenticatedStatsRegistrationStillSendsAccountCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            innerDisco.registerPlaybackWithSession(
                YouTubeClient.WEB,
                "https://s.youtube.com/api/stats/playback",
                "cpn",
                null,
                innerDisco.sessionSnapshot(),
            )

            val request = engine.requestHistory.single()
            assertEquals("SAPISID=secret", request.headers[HttpHeaders.Cookie])
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true)
            assertEquals("0", request.headers["X-Goog-AuthUser"])
        }

    @Test
    fun bulkSessionReplacementNeverPublishesMixedIdentity() {
        runBlocking {
            val innerDisco = InnerDisco(HttpClient(MockEngine { respondOk() }))
            val identities =
                listOf(
                    listOf("cookie-a", "visitor-a", "sync-a", "0"),
                    listOf("cookie-b", "visitor-b", "sync-b", "1"),
                )
            innerDisco.replaceSession("cookie-a", "visitor-a", "sync-a", "0", useLoginForBrowse = true)

            val jobs =
                List(8) { worker ->
                    async(Dispatchers.Default) {
                        repeat(2_000) { iteration ->
                            val identity = identities[(worker + iteration) % identities.size]
                            innerDisco.replaceSession(identity[0], identity[1], identity[2], identity[3], useLoginForBrowse = true)
                            val snapshot = innerDisco.sessionSnapshot()
                            assertTrue(
                                listOf(
                                    snapshot.cookie,
                                    snapshot.visitorData,
                                    snapshot.dataSyncId,
                                    snapshot.authUser,
                                ) in identities,
                            )
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(innerDisco.sessionSnapshot(), innerDisco.sessionFlow.value)
        }
    }

    @Test
    fun mediaContentLengthRejectsUntrustedHostsWithoutSendingHeaders() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine))

            assertNull(
                innerDisco.mediaContentLength(
                    "https://example.com/videoplayback",
                    mapOf(HttpHeaders.Authorization to "Bearer secret"),
                ),
            )
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun mediaContentLengthRejectsUrlUserInfo() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine))

            assertNull(innerDisco.mediaContentLength("https://user@rr1.googlevideo.com/videoplayback", emptyMap()))
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun mediaContentLengthForwardsOnlyMediaHeaders() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("test-agent", request.headers[HttpHeaders.UserAgent])
                    respond("abc", headers = headersOf(HttpHeaders.ContentLength, "3"))
                }
            val innerDisco = InnerDisco(HttpClient(engine))

            val length =
                innerDisco.mediaContentLength(
                    "https://rr1.googlevideo.com/videoplayback",
                    mapOf(
                        HttpHeaders.Authorization to "Bearer secret",
                        HttpHeaders.UserAgent to "test-agent",
                    ),
                )

            assertEquals(3L, length)
        }

    @Test
    fun uploadRejectsUntrustedSessionUrlBeforeSendingCredentials() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Goog-Upload-URL", "https://example.com/upload/session"),
                    )
                }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            assertFailsWith<IllegalArgumentException> {
                innerDisco.uploadSong("track.mp3", byteArrayOf(1))
            }
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun uploadRejectsUnexpectedYouTubeSessionPath() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/not-upload"),
                    )
                }
            val innerDisco = InnerDisco(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            assertFailsWith<IllegalArgumentException> {
                innerDisco.uploadSong("track.mp3", byteArrayOf(1))
            }
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun uploadRejectsOversizedContentBeforeRequest() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = InnerDisco(HttpClient(engine))

            assertFailsWith<IllegalArgumentException> {
                innerDisco.uploadSong("track.mp3", 300L * 1024 * 1024) { ByteReadChannel(byteArrayOf(1)) }
            }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun uploadStreamsKnownLengthContent() =
        runBlocking {
            val content = byteArrayOf(1, 2, 3)
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    if (requests == 1) {
                        assertEquals("upload.youtube.com", request.url.host)
                        assertEquals("/upload/usermusic/http", request.url.encodedPath)
                        assertEquals("0", request.url.parameters["authuser"])
                        assertEquals("start", request.headers["X-Goog-Upload-Command"])
                        assertEquals("resumable", request.headers["X-Goog-Upload-Protocol"])
                        assertEquals(content.size.toString(), request.headers["X-Goog-Upload-Header-Content-Length"])
                        assertNull(request.headers["X-Goog-Upload-Header-Content-Type"])
                        assertTrue(request.body.contentType?.match(ContentType.Application.FormUrlEncoded) == true)
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    "X-Goog-Upload-URL",
                                    "https://upload.youtube.com/?authuser=0&upload_id=session&upload_protocol=resumable",
                                ),
                        )
                    } else {
                        assertEquals("/", request.url.encodedPath)
                        assertEquals("session", request.url.parameters["upload_id"])
                        val body = request.body as OutgoingContent.ReadChannelContent
                        assertEquals(content.size.toLong(), body.contentLength)
                        assertEquals(ContentType.Application.FormUrlEncoded, body.contentType)
                        assertEquals("upload, finalize", request.headers["X-Goog-Upload-Command"])
                        assertEquals("0", request.headers["X-Goog-Upload-Offset"])
                        assertTrue(content.contentEquals(body.readFrom().toByteArray()))
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("X-Goog-Upload-Status", "final"),
                        )
                    }
                }
            val innerDisco = InnerDisco(HttpClient(engine))

            val response = innerDisco.uploadSong("track.mp3", content.size.toLong()) { ByteReadChannel(content) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, requests)
        }

    @Test
    fun playlistThumbnailUploadAppliesEncryptedBlob() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    assertEquals("music.youtube.com", request.url.host)
                    assertEquals(
                        if (requests <= 2) "/playlist_image_upload/playlist_custom_thumbnail" else "/youtubei/v1/browse/edit_playlist",
                        request.url.encodedPath,
                    )
                    when (requests) {
                        1 -> {
                            respond(
                                content = "",
                                status = HttpStatusCode.OK,
                                headers = headersOf("X-Guploader-Uploadid", "upload-id"),
                            )
                        }

                        2 -> {
                            assertEquals("upload-id", request.url.parameters["upload_id"])
                            respond("{\"encryptedBlobId\":\"blob-id\"}", HttpStatusCode.OK)
                        }

                        else -> {
                            val body = (request.body as io.ktor.http.content.TextContent).text
                            assertTrue(body.contains("ACTION_SET_CUSTOM_THUMBNAIL"))
                            assertTrue(body.contains("blob-id"))
                            respondOk()
                        }
                    }
                }
            val innerDisco = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=secret" }

            val response = innerDisco.setPlaylistThumbnail(YouTubeClient.WEB_REMIX, "VLPL123", byteArrayOf(1, 2, 3))

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(3, requests)
            assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true })
        }

    @Test
    fun playlistAndUploadedEntityMutationsUseExpectedPayloads() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=secret" }

            innerDisco.addPlaylistToPlaylist(YouTubeClient.WEB_REMIX, "VLPL123", "VLPL456")
            innerDisco.deletePrivatelyOwnedEntity(YouTubeClient.WEB_REMIX, "entity-id")

            assertEquals(2, engine.requestHistory.size)
            val addBody = (engine.requestHistory[0].body as io.ktor.http.content.TextContent).text
            assertTrue(addBody.contains("ACTION_ADD_PLAYLIST"))
            assertTrue(addBody.contains("\"playlistId\":\"PL123\""))
            assertTrue(addBody.contains("\"addedFullListId\":\"PL456\""))
            val deleteBody = (engine.requestHistory[1].body as io.ktor.http.content.TextContent).text
            assertTrue(deleteBody.contains("\"entityId\":\"entity-id\""))
            assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true })
        }

    @Test
    fun anonymousBrowsePolicySuppressesAccountCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-secret",
                        authUser = "1",
                        useLoginForBrowse = false,
                    )
                }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")
            innerDisco.next(YouTubeClient.WEB_REMIX, videoId = "video-id")
            innerDisco.getQueue(YouTubeClient.WEB_REMIX, videoIds = listOf("video-id"), playlistId = null)

            assertEquals(3, engine.requestHistory.size)
            engine.requestHistory.forEach { request ->
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers["X-Goog-AuthUser"])
                val body = (request.body as io.ktor.http.content.TextContent).text
                assertFalse(body.contains("sync-secret"))
            }
        }

    @Test
    fun explicitBrowseLoginOverridesAnonymousDefault() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-data",
                        authUser = "1",
                        useLoginForBrowse = false,
                    )
                }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_library_landing", setLogin = true)

            val request = engine.requestHistory.single()
            assertTrue(request.headers[HttpHeaders.Cookie]?.contains("SAPISID=account-secret") == true)
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("sync-data"))
        }

    @Test
    fun accountsListUsesWwwWithoutActiveAccountBinding() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "active-account",
                        authUser = "1",
                        useLoginForBrowse = true,
                    )
                }

            innerDisco.accountsList(YouTubeClient.WEB)

            val request = engine.requestHistory.single()
            assertEquals("www.youtube.com", request.url.host)
            assertEquals("/youtubei/v1/account/accounts_list", request.url.encodedPath)
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertFalse(body.contains("active-account"))
        }

    @Test
    fun browseSendsVisitorDataWhenRegionOverrideInactive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.visitorData = "visitor-data" }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_new_releases")

            val request = engine.requestHistory.single()
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))
        }

    @Test
    fun requestLanguageHeaderDoesNotDuplicateExistingRegion() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.locale = YouTubeLocale(gl = "GB", hl = "en-GB") }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")

            assertEquals("en-GB,en;q=0.9", engine.requestHistory.single().headers[HttpHeaders.AcceptLanguage])
        }

    @Test
    fun requestLanguageHeaderAddsConfiguredRegionToBareLanguage() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.locale = YouTubeLocale(gl = "PL", hl = "pl") }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")

            assertEquals("pl-PL,pl;q=0.9", engine.requestHistory.single().headers[HttpHeaders.AcceptLanguage])
        }

    @Test
    fun requestLanguageHeaderOmitsRegionWhenCountryIsEmpty() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.locale = YouTubeLocale(gl = "", hl = "en") }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")

            assertEquals("en", engine.requestHistory.single().headers[HttpHeaders.AcceptLanguage])
        }

    @Test
    fun searchSendsVisitorData() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco = clientWithContentNegotiation(engine).also { it.visitorData = "visitor-data" }

            innerDisco.search(YouTubeClient.WEB_REMIX, query = "test")

            val request = engine.requestHistory.single()
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))
        }

    @Test
    fun searchAuthenticatesByDefault() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-data",
                        authUser = "1",
                        useLoginForBrowse = false,
                    )
                }

            innerDisco.search(YouTubeClient.WEB_REMIX, query = "test")

            val request = engine.requestHistory.single()
            assertTrue(request.headers[HttpHeaders.Cookie]?.contains("SAPISID=account-secret") == true)
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true)
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("sync-data"))
        }

    @Test
    fun searchCanExplicitlyOptOutOfLogin() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-data",
                        authUser = "1",
                        useLoginForBrowse = true,
                    )
                }

            innerDisco.search(YouTubeClient.WEB_REMIX, query = "test", setLogin = false)

            val request = engine.requestHistory.single()
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Goog-AuthUser"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertFalse(body.contains("sync-data"))
        }

    @Test
    fun browseSuppressesVisitorDataWhenRegionOverrideActive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.visitorData = "visitor-data"
                    it.regionOverrideActive = true
                }

            innerDisco.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_new_releases")

            val request = engine.requestHistory.single()
            assertNull(request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":null"))
            assertFalse(body.contains("visitor-data"))
        }

    @Test
    fun searchSuppressesVisitorDataWhenRegionOverrideActive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerDisco =
                clientWithContentNegotiation(engine).also {
                    it.visitorData = "visitor-data"
                    it.regionOverrideActive = true
                }

            innerDisco.search(YouTubeClient.WEB_REMIX, query = "test")

            val request = engine.requestHistory.single()
            assertNull(request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":null"))
            assertFalse(body.contains("visitor-data"))
        }

    private fun clientWithContentNegotiation(engine: MockEngine): InnerDisco {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }
        return InnerDisco(httpClient)
    }

    private fun serviceWorkerResponse(visitorData: String): String {
        val visitorFields = MutableList<String?>(14) { null }.also { it[13] = visitorData }
        return visitorFields.joinToString(prefix = ")]}'\n\n[[\"yt.sw.adr\",null,[[[", postfix = "]]]]]") { value ->
            value?.let { "\"$it\"" } ?: "null"
        }
    }
}
