package com.akylas.enforcedoze.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ServiceResetQueueTest {
    @Test fun concurrentFinishResetCallsSerializePostingUnderTheRuntimeMonitor() {
        val lock = Any()
        val firstPosting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCalling = CountDownLatch(1)
        val secondPosted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val firstJob = Runnable {}
        val secondJob = Runnable {}
        val queue = ServiceResetQueue(lock) { job ->
            assertTrue("posting must own the runtime's monitor", Thread.holdsLock(lock))
            if (job === firstJob) {
                firstPosting.countDown()
                check(releaseFirst.await(3, TimeUnit.SECONDS)) { "first post was not released" }
            } else secondPosted.countDown()
        }
        val first = Thread {
            try { queue.finishReset(firstJob) } catch (error: Throwable) { failure.set(error) }
        }
        val second = Thread {
            try {
                secondCalling.countDown()
                queue.finishReset(secondJob)
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            first.start()
            assertTrue("first finish reached posting", firstPosting.await(2, TimeUnit.SECONDS))
            second.start()
            assertTrue("second finish attempted admission", secondCalling.await(2, TimeUnit.SECONDS))
            assertFalse("second post must wait for the first finish to leave the shared monitor",
                secondPosted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            first.join(4_000)
            second.join(4_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertNull(failure.get())
        assertEquals("both admitted finishes are posted once", 0, secondPosted.count)
    }

    @Test fun finishResetPostsTheOriginalJobWithoutRunningItOnTheCaller() {
        val jobs = mutableListOf<Runnable>()
        val queue = ServiceResetQueue(Any(), jobs::add)
        var ran = false
        val job = Runnable { ran = true }
        queue.finishReset(job)
        assertSame(job, jobs.single())
        assertFalse(ran)
        jobs.single().run()
        assertTrue(ran)
    }
}
