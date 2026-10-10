package com.noxos.triggerrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFileTrackerTest {

    @Test
    fun `several files added in the same second are all accepted (the missed-file bug)`() {
        val tracker = NewFileTracker(initialSeenEpochSeconds = 100)

        // the device case: three files land in one second, delivered across separate polls
        assertTrue(tracker.accept(dateAddedEpochSeconds = 200, id = 1))
        assertTrue(tracker.accept(dateAddedEpochSeconds = 200, id = 2))
        assertTrue(tracker.accept(dateAddedEpochSeconds = 200, id = 3))
    }

    @Test
    fun `a row seen again by the next poll is not scanned twice`() {
        val tracker = NewFileTracker(100)
        assertTrue(tracker.accept(200, 1))

        // the next poll re-selects the same second (query is >=) and now also returns id 2
        assertFalse(tracker.accept(200, 1))
        assertTrue(tracker.accept(200, 2))
        assertFalse(tracker.accept(200, 2))
    }

    @Test
    fun `moving to a later second forgets the earlier second's ids and still accepts new files`() {
        val tracker = NewFileTracker(100)
        assertTrue(tracker.accept(200, 1))

        assertTrue(tracker.accept(201, 2))
        assertEquals(201L, tracker.lastSeenEpochSeconds)
        assertFalse(tracker.accept(201, 2))
    }

    @Test
    fun `rows older than the last second seen are ignored`() {
        val tracker = NewFileTracker(100)
        assertTrue(tracker.accept(205, 9))

        assertFalse(tracker.accept(204, 10))
        assertEquals(205L, tracker.lastSeenEpochSeconds)
    }

    @Test
    fun `files from before monitoring started are ignored but one added in the starting second is not`() {
        val tracker = NewFileTracker(initialSeenEpochSeconds = 100)

        assertFalse(tracker.accept(99, 1))
        assertTrue(tracker.accept(100, 2))
    }
}
