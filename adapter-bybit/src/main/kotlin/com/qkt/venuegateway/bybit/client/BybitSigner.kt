package com.qkt.venuegateway.bybit.client

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bybit v5's HMAC-SHA256 signature in lower-case hex: of `timestamp + key + recvWindow + payload` for REST
 * (the query string of a GET, the body of a POST) and of `GET/realtime<expires>` for the private socket.
 */
internal class BybitSigner(
    private val secret: String,
) {
    fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun toString() = "BybitSigner(***)"
}
