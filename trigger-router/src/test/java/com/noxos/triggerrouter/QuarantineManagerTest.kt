package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.noxos.audit.QuarantineEntry
import com.noxos.audit.QuarantineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuarantineManagerTest {

    private lateinit var context: Context
    private lateinit var repository: FakeQuarantineRepository
    private lateinit var manager: QuarantineManager
    private val testUri = Uri.parse("content://test/downloads/evil.jpg")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = FakeQuarantineRepository()
        manager = QuarantineManager(context, repository)
    }

    @Test
    fun testQuarantineMovesFileBytesIntoPrivateStorageAndRecordsAnEntry() = runBlocking {
        val fileContent = "malformed_exif_bytes"
        shadowOf(context.contentResolver).registerInputStream(
            testUri,
            ByteArrayInputStream(fileContent.toByteArray(Charsets.UTF_8))
        )

        val quarantined = manager.quarantine(testUri, "evil.jpg", "image/jpeg", "Parse error: malformed EXIF")

        assertTrue(quarantined)
        val entry = repository.entries.single()
        assertEquals("evil.jpg", entry.originalDisplayName)
        assertEquals("Parse error: malformed EXIF", entry.reason)

        val storedFile = File(File(context.filesDir, "quarantine"), entry.storedFileName)
        assertTrue(storedFile.exists())
        assertEquals(fileContent, storedFile.readText())
    }

    @Test
    fun aFileLargerThanTheScanLimitIsQuarantinedByStreamingItsBytes() = runBlocking {
        val size = 160L * 1024 * 1024
        shadowOf(context.contentResolver).registerInputStream(testUri, PatternInputStream(size))

        val quarantined = manager.quarantine(testUri, "huge.bin", "application/octet-stream", "File too large")

        assertTrue(quarantined)
        val stored = File(File(context.filesDir, "quarantine"), repository.entries.single().storedFileName)
        assertEquals(size, stored.length())
        stored.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head)
            assertEquals(listOf<Byte>(0, 1, 2, 3), head.toList())
        }
        stored.delete()
    }

    @Test
    fun aCopyThatFailsHalfwayLeavesNoPartialFileNoEntryAndReportsNotQuarantined() = runBlocking {
        shadowOf(context.contentResolver).registerInputStream(testUri, PatternInputStream(10L * 1024 * 1024, failAfter = 1024 * 1024))

        val quarantined = manager.quarantine(testUri, "broken.bin", null, "File too large")

        assertFalse(quarantined)
        assertTrue(repository.entries.isEmpty())
        assertEquals(0, File(context.filesDir, "quarantine").listFiles()?.size ?: 0)
    }

    @Test
    fun testRestoreDeletesThePrivateFileAndTheQuarantineEntry() = runBlocking {
        val fileContent = "malformed_exif_bytes"
        shadowOf(context.contentResolver).registerInputStream(
            testUri,
            ByteArrayInputStream(fileContent.toByteArray(Charsets.UTF_8))
        )
        manager.quarantine(testUri, "evil.jpg", "image/jpeg", "Parse error: malformed EXIF")
        val id = repository.entries.single().id
        val storedFile = File(File(context.filesDir, "quarantine"), repository.entries.single().storedFileName)

        val restored = manager.restore(id)

        assertTrue(restored)
        assertFalse(storedFile.exists())
        assertNull(repository.get(id))
    }

    @Test
    fun testPurgeOlderThanRemovesOnlyStaleEntriesAndTheirFiles() = runBlocking {
        val staleFile = File(File(context.filesDir, "quarantine"), "stale.bin").apply { parentFile?.mkdirs(); writeText("x") }
        val freshFile = File(File(context.filesDir, "quarantine"), "fresh.bin").apply { parentFile?.mkdirs(); writeText("y") }
        val staleId = repository.seed(originalDisplayName = "stale.jpg", storedFileName = "stale.bin", quarantinedAtEpochMillis = 1_000L)
        repository.seed(originalDisplayName = "fresh.jpg", storedFileName = "fresh.bin", quarantinedAtEpochMillis = 9_000L)

        val purged = manager.purgeOlderThan(cutoffEpochMillis = 5_000L)

        assertEquals(1, purged)
        assertFalse(staleFile.exists())
        assertTrue(freshFile.exists())
        assertNull(repository.get(staleId))
    }
}

class PatternInputStream(private val size: Long, private val failAfter: Long = -1) : java.io.InputStream() {
    private var position = 0L

    override fun read(): Int {
        if (position >= size) return -1
        if (failAfter in 0..position) throw java.io.IOException("simulated read failure")
        return (position++ % 251).toInt()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (failAfter in 0..position) throw java.io.IOException("simulated read failure")
        val count = minOf(length.toLong(), size - position).toInt()
        for (i in 0 until count) buffer[offset + i] = ((position + i) % 251).toByte()
        position += count
        return count
    }
}

class FakeQuarantineRepository : QuarantineRepository {
    private var nextId = 1L
    val entries = mutableListOf<QuarantineEntry>()
    private val state = MutableStateFlow<List<QuarantineEntry>>(emptyList())

    fun seed(originalDisplayName: String, storedFileName: String, quarantinedAtEpochMillis: Long): Long {
        val id = nextId++
        entries += QuarantineEntry(id, originalDisplayName, null, storedFileName, "flagged", quarantinedAtEpochMillis)
        state.value = entries.toList()
        return id
    }

    override suspend fun add(originalDisplayName: String, mimeType: String?, storedFileName: String, reason: String): Long {
        val id = nextId++
        entries += QuarantineEntry(id, originalDisplayName, mimeType, storedFileName, reason, System.currentTimeMillis())
        state.value = entries.toList()
        return id
    }

    override fun observeAll(): Flow<List<QuarantineEntry>> = state

    override suspend fun get(id: Long): QuarantineEntry? = entries.find { it.id == id }

    override suspend fun remove(id: Long) {
        entries.removeAll { it.id == id }
        state.value = entries.toList()
    }

    override suspend fun entriesOlderThan(cutoffEpochMillis: Long): List<QuarantineEntry> =
        entries.filter { it.quarantinedAtEpochMillis < cutoffEpochMillis }
}
