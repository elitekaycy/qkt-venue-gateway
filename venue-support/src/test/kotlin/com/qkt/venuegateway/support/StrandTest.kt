package com.qkt.venuegateway.support

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class StrandTest {
    private val pool = Executors.newScheduledThreadPool(4)

    @AfterEach
    fun stop() {
        pool.shutdownNow()
    }

    @Test
    fun `tasks run one at a time in the order submitted, on a pool of several threads`() {
        val strand = Strand(pool, "t")
        val seen = CopyOnWriteArrayList<Int>()
        val inside = AtomicInteger()
        val overlaps = AtomicInteger()
        val done = CountDownLatch(200)
        repeat(200) { i ->
            strand.execute {
                if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
                seen += i
                inside.decrementAndGet()
                done.countDown()
            }
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue
        assertThat(seen).containsExactlyElementsOf(0 until 200)
        assertThat(overlaps.get()).isZero
    }

    @Test
    fun `a periodic task keeps running after a run that throws`() {
        val strand = Strand(pool, "t")
        val runs = AtomicInteger()
        val three = CountDownLatch(3)
        strand.every(0, 20) {
            runs.incrementAndGet()
            three.countDown()
            error("boom")
        }
        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue
        strand.close()
    }

    @Test
    fun `closing a strand stops its own timers and leaves the pool and other strands running`() {
        val a = Strand(pool, "a")
        val b = Strand(pool, "b")
        val aRuns = AtomicInteger()
        val bRan = CountDownLatch(1)
        a.every(0, 10) { aRuns.incrementAndGet() }
        Thread.sleep(100)
        a.close()
        val after = aRuns.get()
        Thread.sleep(100)
        assertThat(aRuns.get()).isBetween(after, after + 1)
        b.schedule(10) { bRan.countDown() }
        assertThat(bRan.await(5, TimeUnit.SECONDS)).isTrue
        assertThat(pool.isShutdown).isFalse
    }

    @Test
    fun `closing with drain runs what was already submitted, closing without drops it`() {
        val gate = CountDownLatch(1)
        val drained = Strand(pool, "d")
        val ran = CopyOnWriteArrayList<String>()
        drained.execute { gate.await() }
        drained.execute { ran += "queued" }
        drained.close(drain = true)
        drained.execute { ran += "after close" }
        gate.countDown()
        Thread.sleep(200)
        assertThat(ran).containsExactly("queued")
        val gate2 = CountDownLatch(1)
        val dropped = Strand(pool, "x")
        dropped.execute { gate2.await() }
        dropped.execute { ran += "dropped" }
        dropped.close()
        gate2.countDown()
        Thread.sleep(200)
        assertThat(ran).containsExactly("queued")
    }

    @Test
    fun `a task due at once runs, however fast the pool is`() {
        repeat(200) {
            val ran = CountDownLatch(1)
            Strand(pool, "now").schedule(0) { ran.countDown() }
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue
        }
    }
}
