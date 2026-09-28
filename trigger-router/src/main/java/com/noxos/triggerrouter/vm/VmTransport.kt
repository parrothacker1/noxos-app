package com.noxos.triggerrouter.vm

import java.io.InputStream

interface VmTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun sendStream(header: ByteArray, source: InputStream, length: Long)
    suspend fun receive(): ByteArray
}
