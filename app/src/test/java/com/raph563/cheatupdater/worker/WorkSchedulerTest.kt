package com.raph563.cheatupdater.worker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class WorkSchedulerTest {
    @Test
    fun `delay before noon is remaining time to noon`() {
        val now = LocalDateTime.of(2026, 2, 16, 10, 0, 0)
        val delay = WorkScheduler.computeDelayUntilNextNoon(now)
        assertEquals(Duration.ofHours(2), delay)
    }

    @Test
    fun `delay after noon is until next day noon`() {
        val now = LocalDateTime.of(2026, 2, 16, 13, 30, 0)
        val delay = WorkScheduler.computeDelayUntilNextNoon(now)
        assertEquals(Duration.ofHours(22).plusMinutes(30), delay)
    }
}
