package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Settles the paper account's expired contracts as Deribit does: at the delivery price its index
 * publishes for the expiry's UTC date, an option at its intrinsic value (`max(D − K, 0)` for a call,
 * `max(K − D, 0)` for a put), a future at the delivery price itself. A delivery price not published yet
 * leaves the position for the next run, every [periodMs].
 */
class PaperSettlement(
    private val market: DeribitMarketData,
    private val listing: PaperListing,
    private val clock: () -> Long,
    private val periodMs: Long = 60_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PaperSettlement::class.java)
    private val timer =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "paper-settlement").apply {
                isDaemon =
                    true
            }
        }

    /** Runs [check] now and every [periodMs]. */
    fun start(check: () -> Unit) {
        timer.scheduleWithFixedDelay({
            runCatching(check).onFailure {
                log.warn("paper settlement check failed: {}", it.message)
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    /** Settles every held contract of [book] that expired by now; returns the settlements made. */
    fun due(book: PaperBook): List<VenueSettlement> {
        val now = clock()
        return book.ledger.positions.keys.toList().mapNotNull { symbol ->
            val instrument = listing.held(symbol)
            val expiry = instrument.expiryMs?.takeIf { it <= now } ?: return@mapNotNull null
            val unit = unitPrice(instrument, deliveryPrice(instrument, expiry) ?: return@mapNotNull null)
            book.settle(symbol, unit, expiry)
        }
    }

    override fun close() {
        timer.shutdownNow()
    }

    private fun deliveryPrice(
        instrument: DeribitInstrument,
        expiryMs: Long,
    ): BigDecimal? {
        val day = Instant.ofEpochMilli(expiryMs).atZone(ZoneOffset.UTC).toLocalDate()
        return market.deliveryPrices(instrument.priceIndex, RECENT_DAYS).firstOrNull { it.first == day }?.second
    }

    private fun unitPrice(
        instrument: DeribitInstrument,
        delivery: BigDecimal,
    ): BigDecimal {
        val strike = instrument.strike ?: return delivery
        val intrinsic = if (instrument.optionType == "call") delivery.subtract(strike) else strike.subtract(delivery)
        return intrinsic.max(BigDecimal.ZERO)
    }

    private companion object {
        const val RECENT_DAYS = 10
    }
}
