package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueErrors
import com.qkt.venuegateway.deribit.client.DeribitException

/**
 * Deribit's failures as the gateway's two: a refusal ([VenueRefusedException]) means Deribit answered
 * and the request did nothing; unavailable ([VenueUnavailableException]) means the outcome is unknown
 * or Deribit is not serving ([DeribitException.unavailable]), so the host looks the order up by its label instead
 * of assuming it failed.
 */
internal object DeribitErrors : VenueErrors(
    "deribit",
    { (it as? DeribitException)?.code },
    { DeribitException.unavailable(it) },
)
