package com.qkt.venuegateway.kalshi.client

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import java.util.HexFormat

/**
 * Kalshi's request signature: the key id [keyId] in `KALSHI-ACCESS-KEY`, the time in milliseconds in
 * `KALSHI-ACCESS-TIMESTAMP`, and in `KALSHI-ACCESS-SIGNATURE` the base64 signature of `timestamp + METHOD + path`
 * (the path from `/trade-api/...`, without its query). The private key [pem] is an Ed25519 key, signed as it is
 * (RFC 8032), or an RSA key, signed RSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt; Kalshi issues both
 * and the PEM header does not say which, so the key itself decides. PKCS#8 (`PRIVATE KEY`) and PKCS#1
 * (`RSA PRIVATE KEY`) PEMs are read. Nothing here prints the key or a signature.
 */
class KalshiAuth(
    val keyId: String,
    pem: String,
) {
    private val key: PrivateKey = parse(pem)

    /** The headers that sign [method] [path] at [timestampMs]. */
    fun headers(
        timestampMs: Long,
        method: String,
        path: String,
    ): Map<String, String> {
        val signer =
            if (key.algorithm == "RSA") {
                Signature.getInstance("RSASSA-PSS").apply {
                    setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, SALT_BYTES, 1))
                }
            } else {
                Signature.getInstance("Ed25519")
            }
        signer.initSign(key)
        signer.update("$timestampMs$method${path.substringBefore('?')}".toByteArray())
        return mapOf(
            "KALSHI-ACCESS-KEY" to keyId,
            "KALSHI-ACCESS-TIMESTAMP" to timestampMs.toString(),
            "KALSHI-ACCESS-SIGNATURE" to Base64.getEncoder().encodeToString(signer.sign()),
        )
    }

    /** The key's algorithm as the JDK names it: `Ed25519` (or `EdDSA`) or `RSA`. */
    val algorithm: String get() = key.algorithm

    override fun toString() = "KalshiAuth(keyId=$keyId, key=***)"

    private companion object {
        const val SALT_BYTES = 32

        /** DER of `AlgorithmIdentifier { rsaEncryption, NULL }`, for wrapping a PKCS#1 key into PKCS#8. */
        val RSA_ALGORITHM: ByteArray = HexFormat.of().parseHex("300d06092a864886f70d0101010500")

        fun parse(pem: String): PrivateKey {
            val text = pem.replace("\\n", "\n")
            val pkcs1 = "BEGIN RSA PRIVATE KEY" in text
            require(pkcs1 || "BEGIN PRIVATE KEY" in text) {
                "the kalshi secret must be the API key's PEM private key (BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY)"
            }
            val body = text.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
            val der = Base64.getMimeDecoder().decode(body)
            if (pkcs1) return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8(der)))
            val spec = PKCS8EncodedKeySpec(der)
            return runCatching { KeyFactory.getInstance("Ed25519").generatePrivate(spec) }
                .recoverCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
                .getOrElse { throw IllegalArgumentException("the kalshi PEM key is neither Ed25519 nor RSA", it) }
        }

        /** `PrivateKeyInfo { 0, rsaEncryption, OCTET STRING pkcs1 }`. */
        fun pkcs8(pkcs1: ByteArray): ByteArray {
            val inner = ByteArrayOutputStream()
            inner.write(byteArrayOf(0x02, 0x01, 0x00))
            inner.write(RSA_ALGORITHM)
            inner.write(der(0x04, pkcs1))
            return der(0x30, inner.toByteArray())
        }

        fun der(
            tag: Int,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            val long = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it.toInt() == 0 }
            out.write(if (n < 0x80) byteArrayOf(n.toByte()) else byteArrayOf((0x80 or long.size).toByte()) + long)
            out.write(content)
            return out.toByteArray()
        }
    }
}
