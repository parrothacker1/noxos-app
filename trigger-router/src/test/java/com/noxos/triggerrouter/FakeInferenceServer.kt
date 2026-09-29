package com.noxos.triggerrouter

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// A minimal one-request-per-connection HTTP server on plain sockets (com.sun.net.httpserver is not on the
// Android unit-test compile path).
class FakeInferenceServer(
    private val status: Int = 200,
    private val responseBody: String = ADVISORY_ALLOW
) : AutoCloseable {

    data class Recorded(val path: String, val body: String, val authorization: String?)

    val requests = CopyOnWriteArrayList<Recorded>()
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val url: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { handle(it) }
                } catch (e: Exception) {
                    if (serverSocket.isClosed) break
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())

        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }

        val path = readLine().split(" ").getOrNull(1) ?: ""
        var contentLength = 0
        var authorization: String? = null
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val value = header.substring(colon + 1).trim()
            when (header.substring(0, colon).trim().lowercase()) {
                "content-length" -> contentLength = value.toInt()
                "authorization" -> authorization = value
            }
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        requests += Recorded(path, String(body, 0, read, Charsets.UTF_8), authorization)

        val payload = responseBody.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status Fake\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(payload)
        out.flush()
    }

    override fun close() {
        serverSocket.close()
    }

    companion object {
        const val ADVISORY_ALLOW =
            "{\"verdict\":\"allow\",\"safety_score\":0.83,\"reasoning\":\"advisory only (permission-based model " +
                "trained on 2012-era apps, do not use this to block): requests few risky permissions\",\"advisory\":true}"
        const val ADVISORY_BLOCK =
            "{\"verdict\":\"block\",\"safety_score\":0.1,\"reasoning\":\"advisory only (permission-based model " +
                "trained on 2012-era apps, do not use this to block): SMS and accessibility permissions\",\"advisory\":true}"
    }
}
