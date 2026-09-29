package com.tmuxer.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowRefreshSchedulingTest {
    @Test
    fun burstDuringARunCollapsesIntoOneFollowUpRun() = runBlocking {
        val gate = Channel<Unit>()
        var runs = 0
        var active = 0
        var maxActive = 0
        val runner = CoalescingRunner(this) {
            runs++
            active++
            maxActive = maxOf(maxActive, active)
            gate.receive()
            active--
        }

        val first = runner.request()
        yield()
        assertEquals(1, runs)

        val burst = List(5) { runner.request() }
        burst.forEach { assertSame(burst.first(), it) }
        assertNotSame(first, burst.first())

        gate.send(Unit)
        first.await()
        // The follow-up run started after the first finished; its waiters are still pending.
        assertFalse(burst.first().isCompleted)
        assertEquals(2, runs)

        gate.send(Unit)
        burst.first().await()
        assertEquals(2, runs)
        assertEquals(1, maxActive)
    }

    @Test
    fun idleRunnerStartsAFreshRunForEachRequest() = runBlocking {
        var runs = 0
        val runner = CoalescingRunner(this) { runs++ }
        runner.run()
        runner.run()
        assertEquals(2, runs)
    }

    @Test
    fun failingRunStillCompletesWaitersAndLaterRequestsRun() = runBlocking {
        var runs = 0
        val runner = CoalescingRunner(this) {
            runs++
            if (runs == 1) throw IllegalStateException("list-windows failed")
        }
        runner.run()
        runner.run()
        assertEquals(2, runs)
    }

    @Test
    fun cancellingTheScopeReleasesWaiters() = runBlocking {
        val scopeJob = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val gate = Channel<Unit>()
        val runner = CoalescingRunner(scope) { gate.receive() }

        val inFlight = runner.request()
        yield()
        val queued = runner.request()
        scopeJob.cancel()
        yield()
        assertTrue(inFlight.isCancelled)
        assertTrue(queued.isCancelled)

        val late = runner.request()
        yield()
        assertTrue(late.isCancelled)
    }

    @Test
    fun olderListingNeverOverwritesANewerOne() {
        val listings = ListingSequencer()
        val older = listings.begin()
        val newer = listings.begin()

        assertTrue(listings.tryApply(newer))
        assertFalse(listings.tryApply(older))
        assertTrue(listings.isSuperseded(older))
    }

    @Test
    fun listingsFinishingInOrderAreAllApplied() {
        val listings = ListingSequencer()
        val first = listings.begin()
        val second = listings.begin()

        assertTrue(listings.tryApply(first))
        assertFalse(listings.isSuperseded(second))
        assertTrue(listings.tryApply(second))
    }

    @Test
    fun invalidateDiscardsListingsStillInFlight() {
        val listings = ListingSequencer()
        val stale = listings.begin()
        listings.invalidate()

        assertTrue(listings.isSuperseded(stale))
        assertFalse(listings.tryApply(stale))
        val fresh = listings.begin()
        assertTrue(listings.tryApply(fresh))
    }

    @Test
    fun pollSlowsDownWhileNotificationsDriveTheList() {
        assertEquals(5_000L, windowPollIntervalMillis(onTerminal = false, observerActive = false))
        assertEquals(15_000L, windowPollIntervalMillis(onTerminal = true, observerActive = false))
        assertEquals(15_000L, windowPollIntervalMillis(onTerminal = false, observerActive = true))
        assertEquals(60_000L, windowPollIntervalMillis(onTerminal = true, observerActive = true))
    }
}
