package com.qkt.venuegateway.alpaca

/**
 * Alpaca gives a replacement order a new `client_order_id`, unique on the account, so the adapter sends the
 * client's label with a generation suffix (`<label>~r1`, `~r2`, ...) and reads the label back by dropping it: every
 * order of a replace chain is found and reported under the label it was placed with, with nothing to remember.
 */
object AlpacaLabels {
    private val SUFFIX = Regex("~r(\\d+)$")

    /** The label [clientOrderId] was placed under. */
    fun labelOf(clientOrderId: String): String = clientOrderId.replace(SUFFIX, "")

    /** The client order id of the replacement of the order now carrying [clientOrderId]. */
    fun next(clientOrderId: String): String {
        val generation =
            SUFFIX
                .find(clientOrderId)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        return "${labelOf(clientOrderId)}~r${generation + 1}"
    }
}
