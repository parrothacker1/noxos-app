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
        listOf("low1", "high1", "low2", "high2").forEach {
            repository.markAutoencoderFlagged(AclKind.NETWORK, it, "autoencoder: flagged, escalating to student")
        }

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

    @Test
    fun testFlagIfUnknownPersistsDestPortAndProtocol() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "pending", destPort = 4444, protocol = "TCP")

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(4444, entry.destPort)
        assertEquals("TCP", entry.protocol)
    }

    @Test
    fun testNextAnalysisBatchOnlyReturnsAutoencoderFlaggedEntries() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "not-flagged", AclPriority.HIGH, "pending")
        repository.flagIfUnknown(AclKind.NETWORK, "flagged", AclPriority.HIGH, "pending")
        repository.markAutoencoderFlagged(AclKind.NETWORK, "flagged", "autoencoder: flagged, escalating to student")

        val batch = repository.nextAnalysisBatch(limit = 10)

        assertEquals(1, batch.size)
        assertEquals("flagged", batch.single().subject)
    }

    @Test
    fun testNextAutoencoderBatchOnlyReturnsUncheckedEntries() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "not-checked", AclPriority.HIGH, "pending")
        repository.flagIfUnknown(AclKind.NETWORK, "checked", AclPriority.HIGH, "pending")
        repository.markAutoencoderFlagged(AclKind.NETWORK, "checked", "autoencoder: flagged, escalating to student")

        val batch = repository.nextAutoencoderBatch(limit = 10)

        assertEquals(1, batch.size)
        assertEquals("not-checked", batch.single().subject)
    }

    @Test
    fun testMarkAutoencoderFlaggedBlocksTrafficAndIsSessionScoped() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "pending")

        repository.markAutoencoderFlagged(AclKind.NETWORK, "1.2.3.4", "autoencoder: flagged, escalating to student")

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(AclState.BLOCKED, entry.state)
        assertTrue(entry.sessionOnly)
        assertEquals("autoencoder: flagged, escalating to student", entry.reason)
    }

    @Test
    fun testMarkAutoencoderFlaggedPreservesCapturedConnectionStats() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "pending", destPort = 443, protocol = "TCP")
        repository.recordConnectionStats(
            AclKind.NETWORK, "1.2.3.4",
            srcPacketCount = 5L, srcByteCount = 500L,
            dstPacketCount = 3L, dstByteCount = 1200L,
            durationMillis = 250L, handshakeLatencyMillis = 40L
        )

        repository.markAutoencoderFlagged(AclKind.NETWORK, "1.2.3.4", "autoencoder: flagged, escalating to student")

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(443, entry.destPort)
        assertEquals("TCP", entry.protocol)
        assertEquals(5L, entry.srcPacketCount)
        assertEquals(500L, entry.srcByteCount)
    }

    @Test
    fun testRecordConnectionStatsPersistsAllSixFields() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "pending")

        repository.recordConnectionStats(
            AclKind.NETWORK, "1.2.3.4",
            srcPacketCount = 5L, srcByteCount = 500L,
            dstPacketCount = 3L, dstByteCount = 1200L,
            durationMillis = 250L, handshakeLatencyMillis = 40L
        )

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(5L, entry.srcPacketCount)
        assertEquals(500L, entry.srcByteCount)
        assertEquals(3L, entry.dstPacketCount)
        assertEquals(1200L, entry.dstByteCount)
        assertEquals(250L, entry.durationMillis)
        assertEquals(40L, entry.handshakeLatencyMillis)
    }

    @Test
    fun testRecordConnectionStatsAllowsNullHandshakeLatencyForNonTcpFlows() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "1.2.3.4", AclPriority.HIGH, "pending", protocol = "UDP")

        repository.recordConnectionStats(
            AclKind.NETWORK, "1.2.3.4",
            srcPacketCount = 2L, srcByteCount = 80L,
            dstPacketCount = 2L, dstByteCount = 80L,
            durationMillis = 100L, handshakeLatencyMillis = null
        )

        val entry = repository.observeKind(AclKind.NETWORK).first().single()
        assertEquals(null, entry.handshakeLatencyMillis)
    }

    @Test
    fun testNextAutoencoderBatchExcludesAStrandedEntryPastTheStalenessWindow() = runBlocking {
        db.aclDao().insertIfAbsent(
            AclEntity(
                AclKind.NETWORK, "stranded", AclState.FLAGGED, AclPriority.HIGH, "pending",
                updatedAtEpochMillis = System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(30)
            )
        )
        repository.flagIfUnknown(AclKind.NETWORK, "fresh", AclPriority.HIGH, "pending")

        val batch = repository.nextAutoencoderBatch(limit = 10)

        assertEquals(1, batch.size)
        assertEquals("fresh", batch.single().subject)
    }

    @Test
    fun testStrandedEntriesDoNotStarveFreshOnesOutOfTheBatch() = runBlocking {
        repeat(5) { i ->
            db.aclDao().insertIfAbsent(
                AclEntity(
                    AclKind.NETWORK, "stranded-$i", AclState.FLAGGED, AclPriority.HIGH, "pending",
                    updatedAtEpochMillis = System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(30)
                )
            )
        }
        repository.flagIfUnknown(AclKind.NETWORK, "fresh", AclPriority.HIGH, "pending")

        val batch = repository.nextAutoencoderBatch(limit = 5)

        assertEquals(1, batch.size)
        assertEquals("fresh", batch.single().subject)
    }
    private suspend fun states(): Map<String, AclState> =
        repository.observeKind(AclKind.NETWORK).first().associate { it.subject to it.state }

    @Test
    fun testResetUnresolvedDropsOnlyPendingFlaggedRowsAndNeverAUserOverrideOrASeededEntry() = runBlocking {
        repository.seedDefaults()
        repository.flagIfUnknown(AclKind.NETWORK, "stale-flagged", AclPriority.HIGH, "new destination, pending analysis")
        repository.block(AclKind.NETWORK, "user-blocked", "blocked manually")
        repository.allow(AclKind.NETWORK, "user-allowed", "allowed manually")
        repository.allow(AclKind.NETWORK, "session-allowed", "autoencoder: normal", sessionOnly = true)

        repository.resetUnresolved(AclKind.NETWORK)

        val remaining = states()
        assertTrue("stale-flagged" !in remaining)
        assertEquals(AclState.BLOCKED, remaining["user-blocked"])
        assertEquals(AclState.ALLOWED, remaining["user-allowed"])
        assertEquals(AclState.ALLOWED, remaining["session-allowed"])
        assertEquals(AclState.ALLOWED, remaining["8.8.8.8"])
    }

    @Test
    fun testResetUnresolvedLeavesOtherKindsAlone() = runBlocking {
        repository.flagIfUnknown(AclKind.FILE_SOURCE, "some.package", AclPriority.LOW, "pending")

        repository.resetUnresolved(AclKind.NETWORK)

        assertEquals(1, repository.observeKind(AclKind.FILE_SOURCE).first().size)
    }

    @Test
    fun testResetAutoencoderVerdictsDropsOldModelVerdictsButKeepsUserOverridesAndStudentVerdicts() = runBlocking {
        repository.allow(AclKind.NETWORK, "ae-normal", "autoencoder: normal", sessionOnly = true)
        repository.flagIfUnknown(AclKind.NETWORK, "ae-flagged", AclPriority.LOW, "pending")
        repository.markAutoencoderFlagged(AclKind.NETWORK, "ae-flagged", "autoencoder: flagged (reconstruction error 0.2), escalating to student")
        repository.allow(AclKind.NETWORK, "student-allowed", "ai: looks fine", sessionOnly = true)
        repository.block(AclKind.NETWORK, "user-blocked", "autoencoder: flagged (user kept it blocked)")
        repository.allow(AclKind.NETWORK, "user-allowed", "allowed manually")

        repository.resetAutoencoderVerdicts(AclKind.NETWORK)

        val remaining = states()
        assertTrue("ae-normal" !in remaining)
        assertTrue("ae-flagged" !in remaining)
        assertEquals(AclState.ALLOWED, remaining["student-allowed"])
        assertEquals(AclState.BLOCKED, remaining["user-blocked"])
        assertEquals(AclState.ALLOWED, remaining["user-allowed"])
    }

    @Test
    fun testAUserOverrideOnAnAutoencoderFlaggedDestinationSurvivesAReset() = runBlocking {
        repository.flagIfUnknown(AclKind.NETWORK, "9.9.9.11", AclPriority.LOW, "pending")
        repository.markAutoencoderFlagged(AclKind.NETWORK, "9.9.9.11", "autoencoder: flagged (reconstruction error 0.25), escalating to student")
        repository.allow(AclKind.NETWORK, "9.9.9.11", "allowed manually")

        repository.resetAutoencoderVerdicts(AclKind.NETWORK)
        repository.resetUnresolved(AclKind.NETWORK)

        assertEquals(AclState.ALLOWED, states()["9.9.9.11"])
    }
}
