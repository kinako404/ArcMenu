package com.fentai.arcmenu.paper.performance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PerformanceFrameTest {
    @Test
    fun `per-player ticks of one server tick count as a single tick`() {
        var tick = 10L
        val metrics = PerformanceMetrics { tick }
        metrics.recordSessionTick(2_000)
        metrics.recordSessionTick(3_000)
        metrics.recordSessionTick(1_000)
        tick = 11L
        metrics.recordSessionTick(4_000)
        tick = 12L
        metrics.recordSessionTick(1_000)

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.ticks)
        assertEquals(5.0, snapshot.averageTickMicros, 1e-9)
        assertEquals(6.0, snapshot.maxTickMicros, 1e-9)
    }

    @Test
    fun `reset discards the open frame`() {
        var tick = 1L
        val metrics = PerformanceMetrics { tick }
        metrics.recordSessionTick(9_000)
        metrics.reset()
        tick = 2L
        metrics.recordSessionTick(1_000)
        tick = 3L
        metrics.recordSessionTick(1_000)
        assertEquals(1, metrics.snapshot().ticks)
        assertEquals(1.0, metrics.snapshot().maxTickMicros, 1e-9)
    }
}
