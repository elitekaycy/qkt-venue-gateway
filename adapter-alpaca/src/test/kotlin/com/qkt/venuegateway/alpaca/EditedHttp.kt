package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaHttp

/** [base]'s recorded answers, except the `METHOD path` keys of [edited], answered with a recorded answer altered. */
internal class EditedHttp(
    private val base: FixtureHttp,
    private val edited: Map<String, String>,
) : AlpacaHttp {
    override fun get(
        path: String,
        params: List<Pair<String, String>>,
    ) = edited["GET $path"] ?: base.get(path, params)

    override fun send(
        method: String,
        path: String,
        body: String,
    ) = edited["$method $path"] ?: base.send(method, path, body)

    override fun delete(path: String) = edited["DELETE $path"] ?: base.delete(path)
}
