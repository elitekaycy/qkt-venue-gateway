package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import java.nio.file.Path

/** [BybitSettings] read from [settings], as the gateway hands them to the adapter. */
internal fun bybitSettings(settings: Map<String, String>) =
    BybitSettings.of(AdapterContext(settings, { 0L }, Path.of(".")))
