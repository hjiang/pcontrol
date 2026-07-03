package com.pcontrol.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncClientTest {

    @Test
    fun `sync sends events and receives policy`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {
                "accepted_event_ids": ["u1", "u2"],
                "policy": {
                    "version": 2,
                    "total_daily_limit_minutes": 120,
                    "warn_threshold_percent": 90,
                    "limits": [],
                    "exclusions": []
                }
            }
        """.trimIndent()))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = listOf(
                SyncEvent(
                    eventId = "u1",
                    kind = "app",
                    subject = "com.game",
                    label = "Game",
                    day = "2026-07-03",
                    startedAt = "2026-07-03T14:58:00Z",
                    durationSeconds = 120
                )
            )
        )

        val response = runBlocking { client.sync(request) }

        assertNotNull("expected non-null response", response)
        assertEquals(listOf("u1", "u2"), response!!.acceptedEventIds)
        assertNotNull("expected policy in response", response.policy)
        assertEquals(2, response.policy!!.version)
        assertEquals(120, response.policy!!.totalDailyLimitMinutes)

        server.shutdown()
    }

    @Test
    fun `sync returns null on network error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = emptyList()
        )

        val response = runBlocking { client.sync(request) }
        assertNull("expected null on error", response)

        server.shutdown()
    }

    @Test
    fun `sync sets bearer token header`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"accepted_event_ids":[],"policy":null}
        """.trimIndent()))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "my-secret-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = emptyList()
        )

        runBlocking { client.sync(request) }

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer my-secret-token", recordedRequest.getHeader("Authorization"))

        server.shutdown()
    }
}
