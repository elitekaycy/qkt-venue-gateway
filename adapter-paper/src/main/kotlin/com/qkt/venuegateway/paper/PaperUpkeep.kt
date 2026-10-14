package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterListener
import org.slf4j.LoggerFactory

/**
 * The paper venue's periodic work on [book], on [settlement]'s timer: prune the market recordings past their
 * retention ([prune], at most once a UTC day; a failure is logged and leaves the rest to run), settle expired
 * contracts, then charge held perpetuals their funding ([funding]), each saved to [store] before it is
 * delivered through [deliver] to the [listener] of the moment. A failed funding read leaves the settlements done.
 */
internal class PaperUpkeep(
    private val book: PaperBook,
    private val store: PaperStore,
    private val settlement: PaperSettlement,
    private val funding: PaperFunding,
    private val prune: () -> Int,
    private val deliver: (Runnable) -> Unit,
    private val listener: () -> AdapterListener?,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PaperUpkeep::class.java)

    /** Runs now and every check period. */
    fun start() = settlement.start(::run)

    override fun close() = settlement.close()

    private fun run() {
        runCatching(prune).onFailure { log.warn("paper recording prune failed: {}", it.message) }
        val settled = synchronized(book) { settlement.due(book).also { if (it.isNotEmpty()) store.save(book) } }
        settled.forEach { s -> deliver { listener()?.settlement(s) } }
        val charged = synchronized(book) { venue { funding.due(book) }.also { if (it.isNotEmpty()) store.save(book) } }
        charged.forEach { f -> deliver { listener()?.funding(f) } }
    }
}
