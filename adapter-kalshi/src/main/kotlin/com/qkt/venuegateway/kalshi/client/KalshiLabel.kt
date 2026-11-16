package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.adapter.VenueRefusedException

/**
 * A client's order label as Kalshi's `client_order_id`. Kalshi takes letters, digits, `_`, `-`, `:` and `=` there,
 * up to 64 characters, and answers anything else `400 invalid_parameters` (probed on the demo on 2026-11-16: `.`
 * and `~` are refused), yet a label may hold any character: qkt ends each of its own with a `.` and a suffix. So a
 * label is sent with every other character as `=` and the two hex digits of each of its UTF-8 bytes (`=` itself as
 * `=3D`), and read back the same way: every label round-trips, and one made only of what Kalshi takes is unchanged.
 */
internal object KalshiLabel {
    private const val MAX = 64
    private const val ESCAPE = '='
    private const val HEX = 16

    /** [label] as Kalshi takes it; refused when it would pass 64 characters. */
    fun encode(label: String): String {
        val sent =
            buildString {
                label.forEach { c ->
                    if (plain(c)) append(c) else c.toString().toByteArray().forEach { append(ESCAPE).append(hex(it)) }
                }
            }
        if (sent.length > MAX) {
            throw VenueRefusedException(
                "kalshi takes an order id of at most $MAX characters: $label needs ${sent.length}",
            )
        }
        return sent
    }

    /** The label Kalshi's [sent] id stands for; an id not written by [encode] is returned as it is. */
    fun decode(sent: String): String {
        if (ESCAPE !in sent) return sent
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < sent.length) {
            if (sent[i] == ESCAPE) {
                val digits = sent.substring(i + 1, minOf(i + 3, sent.length))
                if (digits.length != 2 || !digits.all(::hexDigit)) return sent
                bytes.write(digits.toInt(HEX))
                i += 3
            } else {
                bytes.write(sent[i].code)
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun hexDigit(c: Char) = c in '0'..'9' || c in 'A'..'F'

    private fun plain(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == ':'

    private fun hex(byte: Byte) = "%02X".format(byte.toInt() and 0xFF)
}
