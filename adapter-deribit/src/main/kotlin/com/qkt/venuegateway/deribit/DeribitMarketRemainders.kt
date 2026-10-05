package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitException
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitTrading
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Keeps the wire rule that a market order, or a stop once triggered, never rests. Deribit refuses IOC on
 * both and turns what the book cannot fill into a limit at its price-band edge, where it can fill minutes
 * later far from the market. Every order of [currency] the adapter reads or is pushed passes here: such a
 * remainder is cancelled at once, by its order id, and the order reported as it then stands (`cancelled`
 * with what filled, or `filled` when the remainder filled before the cancel reached it). Orders without a
 * label were not sent through the gateway and are left alone.
 */
internal class DeribitMarketRemainders(
    private val account: DeribitTrading,
    private val currency: String,
) {
    private val log = LoggerFactory.getLogger(DeribitMarketRemainders::class.java)

    /**
     * [o] as it stands once a working remainder of it is cancelled; any other order as it is. The cancel's
     * own answer is the order's state at the moment it was cancelled; when Deribit refuses it because the
     * order is no longer open (it filled first), the order is read again, and the refusal stands only while
     * the remainder still works. Blocks, so never on the push thread.
     */
    fun settled(o: DeribitOrder): DeribitOrder {
        if (!isRemainder(o)) return o
        val now =
            try {
                account.cancel(o.orderId)
            } catch (e: DeribitException) {
                val read = account.ordersByLabel(currency, o.label!!).firstOrNull { it.orderId == o.orderId }
                read?.takeIf { !isRemainder(it) } ?: throw e
            }
        val cancelled = if (now.state == "cancelled") now.amount - now.filledAmount else BigDecimal.ZERO
        log.warn(
            "market order {}: deribit left {} working at {} after {} filled; cancelled {}, now {} with {} filled",
            o.label,
            (o.amount - o.filledAmount).toPlainString(),
            o.price?.toPlainString(),
            o.filledAmount.toPlainString(),
            cancelled.toPlainString(),
            now.state,
            now.filledAmount.toPlainString(),
        )
        return now
    }

    /**
     * Whether the pushed [o] is reported as it is. A working remainder is not: its cancel is sent without
     * waiting (the push thread may not wait), and Deribit pushes the order again once it is cancelled or
     * filled. Should the cancel be lost, the next read of the order ([settled]) cancels it.
     */
    fun reportable(o: DeribitOrder): Boolean {
        if (!isRemainder(o)) return true
        log.warn(
            "market order {}: deribit left {} working at {} after {} filled; cancelling it",
            o.label,
            (o.amount - o.filledAmount).toPlainString(),
            o.price?.toPlainString(),
            o.filledAmount.toPlainString(),
        )
        account.cancelSoon(o.orderId)
        return false
    }

    /** Whether [o] is a market order, or a fired stop-market, Deribit left working with a remainder. */
    private fun isRemainder(o: DeribitOrder) =
        o.label != null && o.state == "open" && (o.originalOrderType ?: o.orderType) in MARKET_TYPES

    private companion object {
        val MARKET_TYPES = setOf("market", "stop_market")
    }
}
