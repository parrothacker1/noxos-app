package com.noxos.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomAclRepositoryTest {

    private lateinit var db: AuditDatabase
    private lateinit var repository: RoomAclRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAclRepository(db.aclDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testFlagIfUnknownNeverOverwritesAnExistingVerdict() = runBlocking {
        repository.block(AclKind.NETWORK, "1.2.3.4", "blocked manually")
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "new destination, pending analysis")

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(AclState.BLOCKED, entry.state)
    }

    @Test
    fun testNetworkAndFileSourceEntriesAreIndependentByKind() = runBlocking {
        repository.block(AclKind.NETWORK, "evil.example", "blocked manually")
        repository.allow(AclKind.FILE_SOURCE, "evil.example", "trusted source")

        val network = repository.observeKind(AclKind.NETWORK).first()
        val fileSource = repository.observeKind(AclKind.FILE_SOURCE).first()
        assertEquals(AclState.BLOCKED, network.single().state)
        assertEquals(AclState.ALLOWED, fileSource.single().state)
    }

    @Test
    fun testNextAnalysisBatchDrainsHighPriorityBeforeLowPriority() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "low1", AclPriority.LOW, "pending")
        repository.flagIfUnknown(AclKind.NETWORK, "high1", AclPriority.HIGH, "pending")
        repository.flagIfUnknown(AclKind.NETWORK, "low2", AclPriority.LOW, "pending")
        repository.flagIfUnknown(AclKind.NETWORK, "high2", AclPriority.HIGH, "pending")

        val batch = repository.nextAnalysisBatch(limit = 3)

        assertEquals(3, batch.size)
        assertTrue(batch.take(2).all { it.priority == AclPriority.HIGH })
        assertEquals(AclPriority.LOW, batch[2].priority)
    }

    @Test
    fun testSeedDefaultsDoesNotOverwriteAnExplicitUserVerdict() = runBlocking {
        val seededHost = AclSeed.WELL_KNOWN_SAFE.first()
        repository.block(AclKind.NETWORK, seededHost, "blocked manually")

        repository.seedDefaults()

        val entry = repository.observeKind(AclKind.NETWORK).first().single { it.subject == seededHost }
        assertEquals(AclState.BLOCKED, entry.state)
        assertEquals("blocked manually", entry.reason)
    }

    @Test
    fun testSeedDefaultsPopulatesFreshHosts() = runBlocking {
        repository.seedDefaults()

        val entries = repository.observeKind(AclKind.NETWORK).first()
        assertEquals(AclSeed.WELL_KNOWN_SAFE.size, entries.size)
        assertTrue(entries.all { it.state == AclState.ALLOWED && it.reason == AclSeed.REASON })
    }

    @Test
    fun testClearSessionVerdictsOnlyRemovesSessionScopedEntries() = runBlocking {
        repository.block(AclKind.NETWORK, "permanent.example", "blocked manually")
        repository.allow(AclKind.NETWORK, "ai-allowed.example", "ai: looks fine", safetyScore = 0.95f, sessionOnly = true)
        repository.block(AclKind.NETWORK, "ai-blocked.example", "ai: known bad actor", safetyScore = 0.02f, sessionOnly = true)

        repository.clearSessionVerdicts(AclKind.NETWORK)

        val remaining = repository.observeKind(AclKind.NETWORK).first()
        assertEquals(1, remaining.size)
        assertEquals("permanent.example", remaining.single().subject)
    }

    @Test
    fun testClearSessionVerdictsIsScopedToKind() = runBlocking {
        repository.allow(AclKind.NETWORK, "1.2.3.4", "ai: fine", sessionOnly = true)
        repository.allow(AclKind.FILE_SOURCE, "com.example.app", "ai: fine", sessionOnly = true)

        repository.clearSessionVerdicts(AclKind.NETWORK)

        assertTrue(repository.observeKind(AclKind.NETWORK).first().isEmpty())
        assertEquals(1, repository.observeKind(AclKind.FILE_SOURCE).first().size)
    }

    @Test
    fun testAllowAndBlockPersistSafetyScore() = runBlocking {
        repository.block(AclKind.NETWORK, "1.2.3.4", "ai: bad", safetyScore = 0.1f, sessionOnly = true)

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(0.1f, entry.safetyScore)
        assertTrue(entry.sessionOnly)
    }
}
