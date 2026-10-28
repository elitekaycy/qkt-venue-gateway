package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import java.nio.file.Path

/** [DeribitSettings] read from [settings], as the gateway hands them to the adapter. */
internal fun deribitSettings(settings: Map<String, String>) =
    DeribitSettings.of(AdapterContext(settings, { 0L }, Path.of(".")))
