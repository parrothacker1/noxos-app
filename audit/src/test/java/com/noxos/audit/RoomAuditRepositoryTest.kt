package com.noxos.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            errorMessage = null
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
        assertNotNull(firstEvent.id)
    }

    @Test
    fun testGetById() = runBlocking {
        val event = AuditEvent(
            timestampEpochMillis = 2000L,
            eventType = AuditEventType.NETWORK_TRAFFIC,
            inputDescriptor = "https://example.com/api",
            outcome = AuditOutcome.ERROR,
            resultSummary = null,
            durationMillis = 50L,
            errorMessage = "Timeout"
        )

        repository.record(event)

        val observed = repository.observeAll().first()
        val savedId = observed[0].id

        val retrieved = repository.get(savedId)
        assertNotNull(retrieved)
        assertEquals(event.timestampEpochMillis, retrieved!!.timestampEpochMillis)
        assertEquals(event.eventType, retrieved.eventType)
        assertEquals(event.inputDescriptor, retrieved.inputDescriptor)
        assertEquals(event.outcome, retrieved.outcome)
        assertEquals(event.resultSummary, retrieved.resultSummary)
        assertEquals(event.durationMillis, retrieved.durationMillis)
        assertEquals(event.errorMessage, retrieved.errorMessage)
    }
}
