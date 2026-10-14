package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.deribit.DeribitListing
import com.qkt.venuegateway.deribit.DeribitMarketMapping
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The paper account's funding records and, per held perpetual, the time of the last rate charged on it.
 * Not thread-safe: the adapter calls it under the book's lock.
 */
class PaperFundingLedger {
    val records = ArrayList<VenueFunding>()
    val chargedThrough = LinkedHashMap<String, Long>()
}

/**
 * Charges held perpetuals their funding as Deribit publishes it: each hourly rate published after a
 * position was first seen costs `quantity × index × rate` (a long pays a positive rate, a short is paid
 * it), rounded to 8 decimals, from the cash balance. A position pays every hour whose rate is published
 * while it is held, the hour it opened included in full (Deribit accrues by the millisecond), so paper
 * funding is close to Deribit's, not equal. A zero charge is no record.
 */
class PaperFunding(
    private val market: DeribitMarketData,
    private val listing: DeribitListing,
    private val currency: String,
    private val clock: () -> Long,
) {
    /** Deribit's published rates of perpetual [code], each hour ending from [fromMs] to [toMs]. */
    fun rates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueFundingRate> = market.fundingRates(code, fromMs, toMs).map(DeribitMarketMapping::fundingRate)

    /**
     * Charges [book]'s held perpetuals every rate published since their last; returns the records made.
     * Every rate is read before anything is charged, so a venue failure charges nothing.
     */
    fun due(book: PaperBook): List<VenueFunding> {
        val now = clock()
        val cursors = book.funding.chargedThrough
        val held = book.ledger.positions.filterKeys { listing.held(it).perpetual }
        cursors.keys.retainAll(held.keys)
        val published = held.keys.associateWith { symbol -> cursors[symbol]?.let { rates(symbol, it + 1, now) } }
        return held.flatMap { (symbol, position) ->
            val rates = published[symbol] ?: return@flatMap emptyList<VenueFunding>().also { cursors[symbol] = now }
            rates.mapNotNull { rate ->
                cursors[symbol] = rate.timeMs
                charge(book, symbol, position.quantity, rate)
            }
        }
    }

    private fun charge(
        book: PaperBook,
        symbol: String,
        quantity: BigDecimal,
        rate: VenueFundingRate,
    ): VenueFunding? {
        val price = rate.price ?: return null
        val amount = quantity.multiply(price).multiply(rate.rate).setScale(SCALE, RoundingMode.HALF_EVEN)
        if (amount.signum() == 0) return null
        book.ledger.balance = book.ledger.balance.subtract(amount)
        return VenueFunding("paper-$symbol-${rate.timeMs}", symbol, amount, currency, quantity, rate.timeMs)
            .also { book.funding.records += it }
    }

    private companion object {
        const val SCALE = 8
    }
}
