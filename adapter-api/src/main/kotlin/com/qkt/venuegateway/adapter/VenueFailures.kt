package com.qkt.venuegateway.adapter

/** The venue refused a request; [reason] is its own. Served as `422 venue_rejected`. */
class VenueRefusedException(
    val reason: String,
) : RuntimeException(reason)

/** The venue cannot be reached. Served as `503 venue_unavailable`. */
class VenueUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
