package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitSettlement
import com.qkt.venuegateway.deribit.client.DeribitTransaction
import java.math.BigDecimal

/**
 * Deribit's expiries as settlements. A `delivery` row (a future) and an `exercise` row (an option) of the
 * settlement history carry the delivery price as `index_price`; a future settles at it, an option at its
 * intrinsic value from it (`max(D − K, 0)` for a call, `max(K − D, 0)` for a put): the row's `mark_price`
 * is 0 for every option, in the money or not, so it is never the price. `settlement` rows (the daily
 * session settlement every position gets) and `bankruptcy` rows are not expiries and are left out; any
 * other type is refused by name. The fee is the `commission` of the transaction log's `expiry` or
 * `delivery` row of the same contract and instant, reported as a delivery fee when Deribit charged one.
 */
internal object DeribitSettlementMapping {
    /** Expiries among [rows], each priced through its [instrument]; the transaction [log] is read only when one expired. */
    fun settlements(
        rows: List<DeribitSettlement>,
        instrument: (String) -> DeribitInstrument,
        log: () -> List<DeribitTransaction>,
    ): List<VenueSettlement> {
        rows
            .firstOrNull {
                it.type !in KNOWN
            }?.let { error("deribit settlement type ${it.type} of ${it.instrument} is unknown") }
        val expiries = rows.filter { it.type in EXPIRIES }
        if (expiries.isEmpty()) return emptyList()
        val fees = log().filter { it.type in FEE_ROWS }
        return expiries.map { row ->
            val fee =
                fees.firstOrNull { it.instrument == row.instrument && it.timestampMs == row.timestampMs }
            VenueSettlement(
                symbol = row.instrument,
                price = unitPrice(instrument(row.instrument), row.indexPrice),
                timeMs = row.timestampMs,
                costs =
                    listOfNotNull(
                        fee?.commission?.takeIf { it.signum() != 0 }?.let {
                            Cost(
                                CostKind.DELIVERY_FEE,
                                it,
                                fee.currency,
                            )
                        },
                    ),
            )
        }
    }

    /** What one unit of [instrument] settles at when its delivery price is [delivery]. */
    fun unitPrice(
        instrument: DeribitInstrument,
        delivery: BigDecimal,
    ): BigDecimal {
        if (instrument.kind != "option") return delivery
        val strike = instrument.strike ?: error("deribit option ${instrument.name} has no strike")
        val intrinsic =
            when (instrument.optionType) {
                "call" -> delivery.subtract(strike)
                "put" -> strike.subtract(delivery)
                else -> error("deribit option ${instrument.name} has option_type ${instrument.optionType}")
            }
        return intrinsic.max(BigDecimal.ZERO)
    }

    private val EXPIRIES = setOf("delivery", "exercise")
    private val KNOWN = EXPIRIES + setOf("settlement", "bankruptcy")
    private val FEE_ROWS = setOf("expiry", "delivery")
}
