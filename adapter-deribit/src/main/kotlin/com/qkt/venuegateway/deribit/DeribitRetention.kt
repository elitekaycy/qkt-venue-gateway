package com.qkt.venuegateway.deribit

/**
 * How many UTC days the gateway keeps of what it records of Deribit's market, which Deribit keeps no history
 * of: settings `depth_retention_days` ([depthDays], `/v1/depth`) and `open_interest_retention_days`
 * ([openInterestDays], `/v1/open-interest`). Each keeps today and that many days before it; the default 0
 * keeps everything, so an upgrade loses nothing. What is pruned before a client fetched it is lost for good.
 */
data class DeribitRetention(
    val depthDays: Int = 0,
    val openInterestDays: Int = 0,
) {
    /** Reads the retention settings, refusing a value that is not a whole number of days by its setting's name. */
    companion object {
        private val DAYS = Regex("\\d{1,6}")

        /** The retention [settings] name, 0 for a setting not given. */
        fun of(settings: Map<String, String>) =
            DeribitRetention(days(settings, "depth_retention_days"), days(settings, "open_interest_retention_days"))

        private fun days(
            settings: Map<String, String>,
            key: String,
        ): Int {
            val value = settings[key] ?: return 0
            require(DAYS.matches(value)) { "setting $key must be a whole number of days, 0 to keep everything: $value" }
            return value.toInt()
        }
    }
}
