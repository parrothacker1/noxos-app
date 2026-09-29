package com.noxos.triggerrouter

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class FakeInferenceServer(
    private val status: Int = 200,
    private val responseBody: String = ADVISORY_ALLOW
) : AutoCloseable {

    data class Recorded(val path: String, val body: String, val authorization: String?)

    val requests = CopyOnWriteArrayList<Recorded>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(exchange.requestURI.path, body, exchange.requestHeaders.getFirst("Authorization"))
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    companion object {
        const val ADVISORY_ALLOW =
            "{\"verdict\":\"allow\",\"safety_score\":0.83,\"reasoning\":\"advisory only (permission-based model " +
                "trained on 2012-era apps, do not use this to block): requests few risky permissions\",\"advisory\":true}"
        const val ADVISORY_BLOCK =
            "{\"verdict\":\"block\",\"safety_score\":0.1,\"reasoning\":\"advisory only (permission-based model " +
                "trained on 2012-era apps, do not use this to block): SMS and accessibility permissions\",\"advisory\":true}"
    }
}
