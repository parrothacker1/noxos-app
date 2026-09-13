package com.noxos.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomQuarantineRepositoryTest {

    private lateinit var db: AuditDatabase
    private lateinit var repository: RoomQuarantineRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomQuarantineRepository(db.quarantineDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAddThenGetRoundTrips() = runBlocking {
        val id = repository.add("evil.jpg", "image/jpeg", "abc123.bin", "Parse error: malformed EXIF")

        val entry = repository.get(id)
        assertEquals("evil.jpg", entry?.originalDisplayName)
        assertEquals("image/jpeg", entry?.mimeType)
        assertEquals("abc123.bin", entry?.storedFileName)
        assertEquals("Parse error: malformed EXIF", entry?.reason)
    }

    @Test
    fun testRemoveDeletesTheEntry() = runBlocking {
        val id = repository.add("evil.jpg", null, "abc123.bin", "flagged")

        repository.remove(id)

        assertNull(repository.get(id))
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun testEntriesOlderThanOnlyReturnsStaleEntries() = runBlocking {
        db.quarantineDao().insert(
            QuarantineEntity(originalDisplayName = "old.jpg", mimeType = null, storedFileName = "old.bin", reason = "flagged", quarantinedAtEpochMillis = 1_000L)
        )
        db.quarantineDao().insert(
            QuarantineEntity(originalDisplayName = "new.jpg", mimeType = null, storedFileName = "new.bin", reason = "flagged", quarantinedAtEpochMillis = 9_000L)
        )

        val stale = repository.entriesOlderThan(cutoffEpochMillis = 5_000L)

        assertEquals(1, stale.size)
        assertEquals("old.jpg", stale.single().originalDisplayName)
    }
}
