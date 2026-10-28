package com.qkt.venuegateway.support

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC signatures as venues ask for them; what is signed is the venue's (its payload format stays in its client). */
object Hmac {
    /** HMAC-SHA256 of [payload] under [secret], lowercase hex. */
    fun sha256Hex(
        secret: String,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
