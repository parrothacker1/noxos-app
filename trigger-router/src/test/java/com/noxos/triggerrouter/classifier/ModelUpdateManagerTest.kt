package com.noxos.triggerrouter.classifier

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelUpdateManagerTest {

    private class FakeHttpServer(body: ByteArray) {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort

        private val thread = Thread {
            try {
                val client = socket.accept()
                client.getInputStream().bufferedReader().use { reader ->
                    while (reader.readLine()?.isNotEmpty() == true) { }
                }
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                client.close()
            } catch (_: Exception) {
            }
        }

        fun start(): FakeHttpServer {
            thread.start()
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
    fun `a checksum mismatch is rejected and the current model is left untouched`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val bundled = ModelUpdateManager(context).loadCurrentModelJson()

        val corruptModelBytes = """{"tampered":true}""".toByteArray()
        val modelServer = FakeHttpServer(corruptModelBytes).start()
        val manifestJson = """{"version":1,"sha256":"${"0".repeat(64)}","modelUrl":"http://127.0.0.1:${modelServer.port}/model"}"""
        val manifestServer = FakeHttpServer(manifestJson.toByteArray()).start()

        val manager = ModelUpdateManager(context, manifestUrl = "http://127.0.0.1:${manifestServer.port}/manifest")

        val result = manager.checkForUpdateIfStale()

        assertTrue(result is ModelUpdateResult.Failed)
        assertEquals(bundled, manager.loadCurrentModelJson())

        manifestServer.stop()
        modelServer.stop()
    }

    @Test
    fun `with no download yet, the bundled placeholder asset is used`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = ModelUpdateManager(context)

        val json = manager.loadCurrentModelJson()

        assertTrue(json.contains("dst_port"))
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
