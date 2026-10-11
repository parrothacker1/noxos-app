package com.noxos.triggerrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpTest {

    private val now = 1_000_000L

    private fun file(id: Long, date: Long) = ArrivedFile(id, date, null, "f$id")

    @Test
    fun `with nothing persisted there is no catch-up, only files from now on`() {
        assertEquals(now, CatchUp.initialSeenSeconds(0L, now))
    }

    @Test
    fun `catch-up starts just after the last seen second so that file is not rescanned`() {
        val persisted = now - 600
        val tracker = NewFileTracker(CatchUp.initialSeenSeconds(persisted, now))
        assertFalse(tracker.accept(persisted, 1))
        assertTrue(tracker.accept(persisted + 1, 2))
    }

    @Test
    fun `catch-up never reaches further back than the age bound`() {
        val longAgo = now - 30 * 24 * 60 * 60L
        assertEquals(now - CatchUp.MAX_AGE_SECONDS, CatchUp.initialSeenSeconds(longAgo, now))
    }

    @Test
    fun `a flood of files is cut to the newest ones`() {
        val flood = (1L..100L).map { file(it, now - 1000 + it) }
        val kept = CatchUp.newest(flood)
        assertEquals(CatchUp.MAX_FILES, kept.size)
        assertEquals(flood.takeLast(CatchUp.MAX_FILES), kept)
    }

    @Test
    fun `a small batch is kept whole and in order`() {
        val few = listOf(file(1, 10), file(2, 11), file(3, 12))
        assertEquals(few, CatchUp.newest(few))
    }

    @Test
    fun `an unsorted flood is cut by date, not by position`() {
        val shuffled = (1L..30L).map { file(it, if (it % 2 == 0L) 500 + it else it) }
        val kept = CatchUp.newest(shuffled, limit = 5)
        assertEquals(5, kept.size)
        assertTrue(kept.all { it.dateAddedEpochSeconds >= 500 })
    }
}
