package com.noxos.triggerrouter.classifier

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelUpdateManagerTest {

    @Before
    @After
    fun clearAnyLocalModelState() {
        val context: Context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "on_device_network_model.json").delete()
        File(context.filesDir, "on_device_network_model.meta").delete()
    }

    private class FakeHttpServer(body: ByteArray) {
        private val socket = ServerSocket(0)
        private val ready = java.util.concurrent.CountDownLatch(1)
        val port: Int get() = socket.localPort

        private val thread = Thread {
            ready.countDown()
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.getInputStream().bufferedReader().use { reader ->
                        while (reader.readLine()?.isNotEmpty() == true) { }
                    }
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    client.close()
                }
            }
        }

        fun start(): FakeHttpServer {
            thread.start()
            ready.await()
            return this
        }

        fun stop() = socket.close()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a newer, checksum-valid model is downloaded and becomes the current model`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val newModelBytes = """{"base_score":0.0,"trees":[]}""".toByteArray()
        val modelServer = FakeHttpServer(newModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${sha256Hex(newModelBytes)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()

        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")

        val result = manager.checkForUpdateIfStale()

        assertEquals(ModelUpdateResult.Updated(1), result)
        assertEquals(String(newModelBytes), manager.loadCurrentModelJson())

        manifestServer.stop()
        modelServer.stop()
    }

    @Test
    fun `a checksum mismatch is rejected and no model becomes current`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val corruptModelBytes = """{"tampered":true}""".toByteArray()
        val modelServer = FakeHttpServer(corruptModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${"0".repeat(64)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()

        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")

        val result = manager.checkForUpdateIfStale()

        assertTrue(result is ModelUpdateResult.Failed)
        assertFalse(manager.hasLocalModel())
        assertNull(manager.loadCurrentModelJson())

        manifestServer.stop()
        modelServer.stop()
    }

    @Test
    fun `with no download ever attempted, there is no local model`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = ModelUpdateManager(context)

        assertFalse(manager.hasLocalModel())
        assertNull(manager.loadCurrentModelJson())
    }

    @Test
    fun `ensureModelLoaded fetches immediately when no local model exists yet, ignoring the staleness window`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val newModelBytes = """{"base_score":0.0,"trees":[]}""".toByteArray()
        val modelServer = FakeHttpServer(newModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${sha256Hex(newModelBytes)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()
        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")

        val loaded = manager.ensureModelLoaded()

        assertTrue(loaded)
        assertTrue(manager.hasLocalModel())
        assertEquals(String(newModelBytes), manager.loadCurrentModelJson())

        manifestServer.stop()
        modelServer.stop()
    }

    @Test
    fun `ensureModelLoaded returns false and stays false when the manifest is unreachable`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:1/manifest")

        val loaded = manager.ensureModelLoaded()

        assertFalse(loaded)
        assertFalse(manager.hasLocalModel())
    }

    @Test
    fun `ensureModelLoaded is a cheap no-op once a model is already local`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val newModelBytes = """{"base_score":0.0,"trees":[]}""".toByteArray()
        val modelServer = FakeHttpServer(newModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${sha256Hex(newModelBytes)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()
        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")
        manager.ensureModelLoaded()
        manifestServer.stop()
        modelServer.stop()

        val secondCall = manager.ensureModelLoaded()

        assertTrue(secondCall)
    }

    @Test
    fun `checking again before the staleness window elapses does not contact the server`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val newModelBytes = """{"base_score":0.0,"trees":[]}""".toByteArray()
        val modelServer = FakeHttpServer(newModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${sha256Hex(newModelBytes)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()
        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")
        manager.checkForUpdateIfStale()
        manifestServer.stop()
        modelServer.stop()

        val secondResult = manager.checkForUpdateIfStale()

        assertEquals(ModelUpdateResult.UpToDate, secondResult)
    }
}
