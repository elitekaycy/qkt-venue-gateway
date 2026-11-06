package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiAuthTest {
    private fun pem(
        type: String,
        der: ByteArray,
    ) = "-----BEGIN $type-----\n${Base64.getMimeEncoder().encodeToString(der)}\n-----END $type-----\n"

    private fun verify(
        algorithm: String,
        key: PublicKey,
        headers: Map<String, String>,
        text: String,
    ): Boolean {
        val verifier = Signature.getInstance(algorithm)
        if (algorithm == "RSASSA-PSS") {
            verifier.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        }
        verifier.initVerify(key)
        verifier.update(text.toByteArray())
        return verifier.verify(Base64.getDecoder().decode(headers.getValue("KALSHI-ACCESS-SIGNATURE")))
    }

    @Test
    fun `an ed25519 key signs the time, the method and the path without its query`() {
        val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val auth = KalshiAuth("key-id", pem("PRIVATE KEY", pair.private.encoded))

        val headers = auth.headers(1_793_927_004_577L, "GET", "/trade-api/v2/portfolio/orders?limit=5")

        assertThat(headers["KALSHI-ACCESS-KEY"]).isEqualTo("key-id")
        assertThat(headers["KALSHI-ACCESS-TIMESTAMP"]).isEqualTo("1793927004577")
        val signed = "1793927004577GET/trade-api/v2/portfolio/orders"
        assertThat(verify("Ed25519", pair.public, headers, signed)).isTrue
        assertThat(auth.algorithm).isIn("Ed25519", "EdDSA")
    }

    @Test
    fun `an rsa key signs rsa-pss with sha-256, from a pkcs8 or a pkcs1 pem`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val pkcs1 = pkcs8.copyOfRange(PKCS1_OFFSET, pkcs8.size)

        listOf(pem("PRIVATE KEY", pkcs8), pem("RSA PRIVATE KEY", pkcs1)).forEach { text ->
            val auth = KalshiAuth("key-id", text)
            val headers = auth.headers(5L, "POST", "/trade-api/v2/portfolio/events/orders")
            assertThat(auth.algorithm).isEqualTo("RSA")
            assertThat(verify("RSASSA-PSS", pair.public, headers, "5POST/trade-api/v2/portfolio/events/orders")).isTrue
        }
        assertThat(KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8)))
            .isNotNull
    }

    @Test
    fun `a pem passed with escaped newlines, as one environment line, is read`() {
        val auth = KalshiAuth("key-id", Fixtures.pem.replace("\n", "\\n"))

        assertThat(auth.headers(1L, "GET", "/x")).containsKey("KALSHI-ACCESS-SIGNATURE")
    }

    @Test
    fun `a secret that is not a pem private key is refused without echoing it`() {
        assertThatThrownBy { KalshiAuth("key-id", "not-a-key") }
            .hasMessageContaining("PEM private key")
            .hasMessageNotContaining("not-a-key")
        val ec = KeyPairGenerator.getInstance("EC").generateKeyPair().private.encoded
        assertThatThrownBy { KalshiAuth("key-id", pem("PRIVATE KEY", ec)) }
            .hasMessageContaining("neither Ed25519 nor RSA")
    }

    @Test
    fun `the key never prints`() {
        assertThat(KalshiAuth("key-id", Fixtures.pem).toString()).isEqualTo("KalshiAuth(keyId=key-id, key=***)")
    }

    private companion object {
        /** Where a 2048-bit RSA PKCS#8 key's PKCS#1 body starts, after its four heads. */
        const val PKCS1_OFFSET = 26
    }
}
