package com.fentai.arcmenu.paper.platform

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ArcFileChannelTest {
    private val globalThread = Executors.newSingleThreadExecutor { Thread(it, "test-global") }
    private val enabled = AtomicBoolean(true)
    private val channel = ArcFileChannel("test-files", enabled::get) { globalThread.execute(it) }

    @AfterEach
    fun shutdown() {
        channel.close()
        globalThread.shutdownNow()
    }

    @Test
    fun `operations submitted from many threads never overlap and keep per-caller order`() {
        val inside = AtomicInteger()
        val overlaps = AtomicInteger()
        val order = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Int>>()
        val callers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (0 until 8).map { caller ->
            callers.submit<List<java.util.concurrent.CompletableFuture<Unit>>> {
                start.await()
                (0 until 50).map { step ->
                    channel.submit {
                        if (inside.incrementAndGet() != 1) overlaps.incrementAndGet()
                        synchronized(order) { order.getOrPut(caller) { mutableListOf() } += step }
                        inside.decrementAndGet()
                        Unit
                    }
                }
            }
        }
        start.countDown()
        futures.flatMap { it.get(10, TimeUnit.SECONDS) }.forEach { it.get(10, TimeUnit.SECONDS) }
        callers.shutdownNow()
        assertEquals(0, overlaps.get())
        order.values.forEach { assertEquals((0 until 50).toList(), it) }
    }

    @Test
    fun `nested submissions run inline instead of deadlocking`() {
        val result = channel.submit { channel.submit { channel.owns() }.get(1, TimeUnit.SECONDS) }.get(5, TimeUnit.SECONDS)
        assertTrue(result)
        assertFalse(channel.owns())
    }

    @Test
    fun `global publication runs on the global thread and returns to the channel`() {
        val threads = channel.submit {
            val publishing = channel.onGlobal { Thread.currentThread().name }
            publishing to Thread.currentThread().name
        }.get(5, TimeUnit.SECONDS)
        assertEquals("test-global", threads.first)
        assertEquals("test-files", threads.second)
    }

    @Test
    fun `global failures propagate to the waiting file operation`() {
        val error = assertThrows<java.util.concurrent.ExecutionException> {
            channel.submit { channel.onGlobal<Unit> { throw IllegalArgumentException("conflict") } }.get(5, TimeUnit.SECONDS)
        }
        assertTrue(error.cause is IllegalArgumentException)
        assertEquals("conflict", error.cause?.message)
    }

    @Test
    fun `waiting for the global region aborts when the plugin is disabled`() {
        val stalled = ArcFileChannel("stalled-files", enabled::get) { }
        try {
            val pending = stalled.submit { stalled.onGlobal { "never" } }
            Thread.sleep(150)
            enabled.set(false)
            val error = assertThrows<java.util.concurrent.ExecutionException> { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is IllegalStateException)
        } finally {
            stalled.close()
        }
    }

    @Test
    fun `global publication is rejected outside the channel`() {
        assertThrows<IllegalStateException> { channel.onGlobal { Unit } }
    }

    @Test
    fun `worker threads may wait for file results`() {
        val caller = Thread.currentThread()
        val worker = channel.call { Thread.currentThread() }
        assertNotSame(caller, worker)
        assertSame(worker, channel.call { Thread.currentThread() })
    }

    @Test
    fun `closing finishes queued work and rejects new work`() {
        val ran = AtomicBoolean(false)
        val local = ArcFileChannel("closing-files", { true }) { it.run() }
        local.submit { Thread.sleep(100); ran.set(true) }
        local.close()
        assertTrue(ran.get())
        assertThrows<java.util.concurrent.ExecutionException> { local.submit { Unit }.get(1, TimeUnit.SECONDS) }
    }
}
