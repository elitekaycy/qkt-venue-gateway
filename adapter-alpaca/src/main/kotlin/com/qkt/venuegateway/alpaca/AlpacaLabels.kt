package com.qkt.venuegateway.alpaca

/**
 * Alpaca gives a replacement order a new `client_order_id`, unique on the account, so the adapter sends the
 * client's label with a generation suffix (`<label>~r1`, `~r2`, ...) and reads the label back by dropping it: every
 * order of a replace chain is found and reported under the label it was placed with, with nothing to remember.
 * A target re-sent as an OCO with a stop ([AlpacaExitPairs]) carries the stop's label after `~o`
 * (`<target>~r1~o<stop>`), since Alpaca names the stop leg itself and refuses to rename it; a client label never
 * holds `~o`.
 */
object AlpacaLabels {
    private val SUFFIX = Regex("~r(\\d+)$")
    private const val PAIR = "~o"

    /** The longest `client_order_id` Alpaca takes. */
    const val MAX_LENGTH = 128

    /** The label [clientOrderId] was placed under. */
    fun labelOf(clientOrderId: String): String = clientOrderId.substringBefore(PAIR).replace(SUFFIX, "")

    /** The client order id of the replacement of the order now carrying [clientOrderId]; a pair stays a pair. */
    fun next(clientOrderId: String): String {
        val own = clientOrderId.substringBefore(PAIR)
        val generation =
            SUFFIX
                .find(own)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        return "${labelOf(own)}~r${generation + 1}${clientOrderId.substring(own.length)}"
    }

    /** The client order id of the OCO that re-sends the target now carrying [targetId] with the stop [stopLabel]. */
    fun paired(
        targetId: String,
        stopLabel: String,
    ): String = "${next(targetId)}$PAIR$stopLabel"

    /** The label of the stop an OCO's [clientOrderId] pairs with its target, or null for any other order. */
    fun stopOf(clientOrderId: String): String? = clientOrderId.substringAfter(PAIR, "").ifEmpty { null }
}
