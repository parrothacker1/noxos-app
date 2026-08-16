package com.noxos.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomAuditRepositoryTest {

    private lateinit var db: AuditDatabase
    private lateinit var repository: RoomAuditRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAuditRepository(db.auditDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRecordAndObserve() = runBlocking {
        val event = AuditEvent(
            timestampEpochMillis = 1000L,
            eventType = AuditEventType.FILE_SCAN,
            inputDescriptor = "test.jpg",
            outcome = AuditOutcome.SUCCESS,
            resultSummary = "EXIF clean",
            durationMillis = 120L,
            errorMessage = null,
            flagged = false,
            remoteHost = null,
            stepTimingsCsv = "BOOTING:400,EXECUTING:1800"
        )

        repository.record(event)

        val observed = repository.observeAll().first()
        assertEquals(1, observed.size)
        val firstEvent = observed[0]
        assertEquals(event.timestampEpochMillis, firstEvent.timestampEpochMillis)
        assertEquals(event.eventType, firstEvent.eventType)
        assertEquals(event.inputDescriptor, firstEvent.inputDescriptor)
        assertEquals(event.outcome, firstEvent.outcome)
        assertEquals(event.resultSummary, firstEvent.resultSummary)
        assertEquals(event.durationMillis, firstEvent.durationMillis)
        assertEquals(event.errorMessage, firstEvent.errorMessage)
        assertEquals(event.flagged, firstEvent.flagged)
        assertEquals(event.remoteHost, firstEvent.remoteHost)
        assertEquals(event.stepTimingsCsv, firstEvent.stepTimingsCsv)
        assertNotNull(firstEvent.id)
    }

    @Test
    fun testGetById() = runBlocking {
        val event = AuditEvent(
            timestampEpochMillis = 2000L,
            eventType = AuditEventType.NETWORK_TRAFFIC,
            inputDescriptor = "10.0.0.4:1234 -> 91.203.5.12:443",
            outcome = AuditOutcome.BLOCKED,
            resultSummary = "blocked host",
            durationMillis = 0L,
            errorMessage = null,
            flagged = false,
            remoteHost = "91.203.5.12"
        )

        repository.record(event)

        val observed = repository.observeAll().first()
        val savedId = observed[0].id

        val retrieved = repository.get(savedId)
        assertNotNull(retrieved)
        assertEquals(event.outcome, retrieved!!.outcome)
        assertEquals(event.remoteHost, retrieved.remoteHost)
    }

    @Test
    fun testSetFlagged() = runBlocking {
        val event = AuditEvent(
            timestampEpochMillis = 3000L,
            eventType = AuditEventType.FILE_SCAN,
            inputDescriptor = "unknown.bin",
            outcome = AuditOutcome.SUCCESS,
            resultSummary = null,
            durationMillis = 10L,
            errorMessage = null
        )
        repository.record(event)
        val savedId = repository.observeAll().first()[0].id
        assertFalse(repository.get(savedId)!!.flagged)

        repository.setFlagged(savedId, true)

        assertTrue(repository.get(savedId)!!.flagged)
    }

    @Test
    fun testPurgeOlderThan() = runBlocking {
        repository.record(
            AuditEvent(
                timestampEpochMillis = 1000L,
                eventType = AuditEventType.FILE_SCAN,
                inputDescriptor = "old.jpg",
                outcome = AuditOutcome.SUCCESS,
                resultSummary = null,
                durationMillis = 10L,
                errorMessage = null
            )
        )
        repository.record(
            AuditEvent(
                timestampEpochMillis = 5000L,
                eventType = AuditEventType.FILE_SCAN,
                inputDescriptor = "new.jpg",
                outcome = AuditOutcome.SUCCESS,
                resultSummary = null,
                durationMillis = 10L,
                errorMessage = null
            )
        )

        val deleted = repository.purgeOlderThan(cutoffEpochMillis = 3000L)

        assertEquals(1, deleted)
        val remaining = repository.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals("new.jpg", remaining[0].inputDescriptor)
    }
}
