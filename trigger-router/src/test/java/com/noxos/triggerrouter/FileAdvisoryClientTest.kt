package com.noxos.triggerrouter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileAdvisoryClientTest {

    private val perms = listOf("android.permission.INTERNET", "android.permission.CAMERA")

    @Test
    fun `the request body is only the deduplicated permission list, to analyze-file, with bearer auth`() {
        FakeInferenceServer().use { server ->
            val advisory = FileAdvisoryClient.request(
                server.url + "/", "secret",
                listOf("android.permission.INTERNET", "android.permission.INTERNET", "android.permission.CAMERA", "  ")
            )

            assertNotNull(advisory)
            val request = server.requests.single()
            assertEquals("/analyze/file", request.path)
            assertEquals(
                "{\"permission_strings\":[\"android.permission.INTERNET\",\"android.permission.CAMERA\"]}",
                request.body
            )
            assertEquals("Bearer secret", request.authorization)
        }
    }

    @Test
    fun `no authorization header is sent when the api key is blank`() {
        FakeInferenceServer().use { server ->
            FileAdvisoryClient.request(server.url, "", perms)

            assertNull(server.requests.single().authorization)
        }
    }

    @Test
    fun `more than 2000 permissions are cut to the server's cap`() {
        FakeInferenceServer().use { server ->
            FileAdvisoryClient.request(server.url, "k", (1..2500).map { "android.permission.P$it" })

            val sent = JSONObject(server.requests.single().body).getJSONArray("permission_strings")
            assertEquals(2000, sent.length())
            assertEquals("android.permission.P1", sent.getString(0))
        }
    }

    @Test
    fun `an empty or blank list sends nothing at all`() {
        FakeInferenceServer().use { server ->
            assertNull(FileAdvisoryClient.request(server.url, "k", emptyList()))
            assertNull(FileAdvisoryClient.request(server.url, "k", listOf("", "  ")))

            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `a good advisory response is parsed with its wording intact`() {
        FakeInferenceServer(responseBody = FakeInferenceServer.ADVISORY_BLOCK).use { server ->
            val advisory = FileAdvisoryClient.request(server.url, "k", perms)!!

            assertEquals("block", advisory.verdict)
            assertEquals(0.1f, advisory.safetyScore!!, 1e-6f)
            assertTrue(advisory.displayText.startsWith("advisory only (permission-based model trained on 2012-era apps"))
        }
    }

    @Test
    fun `auth errors, bad requests and outages give no advisory`() {
        listOf(401, 400, 503).forEach { status ->
            FakeInferenceServer(status = status, responseBody = "{}").use { server ->
                assertNull("HTTP $status", FileAdvisoryClient.request(server.url, "k", perms))
            }
        }
    }

    @Test
    fun `a response that is not marked advisory or is malformed is discarded`() {
        FakeInferenceServer(responseBody = "{\"verdict\":\"allow\",\"safety_score\":0.9,\"reasoning\":\"looks fine\"}").use {
            assertNull(FileAdvisoryClient.request(it.url, "k", perms))
        }
        FakeInferenceServer(responseBody = "not json").use {
            assertNull(FileAdvisoryClient.request(it.url, "k", perms))
        }
        FakeInferenceServer(responseBody = "{\"advisory\":true,\"verdict\":\"allow\"}").use {
            assertNull(FileAdvisoryClient.request(it.url, "k", perms))
        }
    }

    @Test
    fun `an unreachable server gives no advisory instead of throwing`() {
        // Port 1 (tcpmux) is closed on any normal host, so the connection is refused immediately.
        assertNull(FileAdvisoryClient.request("http://127.0.0.1:1", "k", perms))
    }

    @Test
    fun `the advisory wording is guaranteed to stay visible even if the server omits it`() {
        assertEquals("advisory only: looks fine", FileAdvisory("allow", 0.9f, "looks fine").displayText)
        assertEquals("Advisory only (x): fine", FileAdvisory("allow", 0.9f, "Advisory only (x): fine").displayText)
    }
}
