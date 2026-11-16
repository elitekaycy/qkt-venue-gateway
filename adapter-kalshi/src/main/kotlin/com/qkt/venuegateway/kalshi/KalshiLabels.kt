package com.qkt.venuegateway.kalshi

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Which Kalshi order id each label (the client's `client_order_id`) names. Kalshi finds an order only by its id, so
 * every id the gateway learns (from a placement, a push, or a listing of the account's orders) is kept, and appended
 * to `labels.csv` under [stateDir] (`label,order_id`), so a label is found by id after a restart too. What was sent
 * under a label ([KalshiSent]) is kept for this run only: Kalshi's order does not echo it.
 */
internal class KalshiLabels(
    stateDir: Path,
) {
    private val file = stateDir.resolve("labels.csv")
    private val ids = ConcurrentHashMap<String, String>()
    private val labels = ConcurrentHashMap<String, String>()
    private val sent = ConcurrentHashMap<String, KalshiSent>()

    init {
        if (Files.exists(file)) {
            Files.readAllLines(file).filter { it.contains(',') }.forEach { line ->
                val (label, id) = line.split(',', limit = 2)
                ids[label] = id
                labels[id] = label
            }
        }
    }

    /** The order id [label] names, when known. */
    fun id(label: String): String? = ids[label]

    /** The label of order [orderId], when known. */
    fun label(orderId: String): String? = labels[orderId]

    /** Remembers that [label] names [orderId], keeping it across restarts. */
    fun learn(
        label: String,
        orderId: String,
    ) {
        if (ids.put(label, orderId) == orderId) return
        labels[orderId] = label
        synchronized(this) {
            Files.createDirectories(file.parent)
            Files.writeString(file, "$label,$orderId\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    fun sent(
        label: String,
        order: KalshiSent,
    ) {
        sent[label] = order
    }

    fun sent(label: String): KalshiSent? = sent[label]
}
