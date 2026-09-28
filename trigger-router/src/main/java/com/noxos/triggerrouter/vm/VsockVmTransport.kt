package com.noxos.triggerrouter.vm

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

class VsockVmTransport(private val pfd: ParcelFileDescriptor) : VmTransport {
    private val inputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    private val dataInput = DataInputStream(inputStream)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        outputStream.write(bytes)
        outputStream.flush()
    }

    override suspend fun sendStream(header: ByteArray, source: InputStream, length: Long) = withContext(Dispatchers.IO) {
        outputStream.write(header)
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        var remaining = length
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) throw EOFException("source ended with $remaining bytes still expected")
            outputStream.write(buffer, 0, read)
            remaining -= read
        }
        outputStream.flush()
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val length = dataInput.readInt()
        if (length < 0 || length > 10 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid response length: $length")
        }
        val bytes = ByteArray(length)
        dataInput.readFully(bytes)
        bytes
    }

    companion object {
        private const val STREAM_CHUNK_BYTES = 64 * 1024
    }
}
