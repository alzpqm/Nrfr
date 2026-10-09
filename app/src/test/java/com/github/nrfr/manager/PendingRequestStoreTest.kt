package com.github.nrfr.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PendingRequestStoreTest {
    private var now = 1_000L
    private val store = PendingRequestStore<String>(15_000L) { now }

    @Test fun tokenCanOnlyBeConsumedOnce() {
        val token = store.add(42, "request")
        assertEquals("request", store.take(token, 42))
        assertNull(store.take(token, 42))
    }

    @Test fun wrongProcessFailsClosed() {
        val token = store.add(42, "request")
        assertNull(store.take(token, 43))
        assertNull(store.take(token, 42))
    }

    @Test fun expiredTokenCannotWrite() {
        val token = store.add(42, "request")
        now += 15_000L
        assertNull(store.take(token, 42))
    }

    @Test fun unknownOrCancelledTokenCannotWrite() {
        assertNull(store.take("not-a-request", 42))
        val token = store.add(42, "request")
        store.remove(token)
        assertNull(store.take(token, 42))
    }

    @Test fun simultaneousConsumersCannotReplayARequest() {
        val token = store.add(42, "request")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { pool.submit<String?> { gate.await(); store.take(token, 42) } }
            gate.countDown()
            assertEquals(1, attempts.count { it.get() == "request" })
        } finally {
            pool.shutdownNow()
        }
    }
}
