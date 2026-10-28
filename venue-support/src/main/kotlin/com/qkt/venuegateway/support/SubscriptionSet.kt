package com.qkt.venuegateway.support

/**
 * What a socket should be subscribed to, kept across reconnects: [want] answers only what is new (to send now, on a
 * link that is up), [drop] only what was there, and [all] is what a link that comes up subscribes to. Thread-safe.
 */
class SubscriptionSet<T> {
    private val wanted = LinkedHashSet<T>()

    @Synchronized
    fun want(items: Collection<T>): List<T> = items.filter(wanted::add)

    @Synchronized
    fun drop(items: Collection<T>): List<T> = items.filter(wanted::remove)

    @Synchronized
    fun all(): List<T> = wanted.toList()
}
