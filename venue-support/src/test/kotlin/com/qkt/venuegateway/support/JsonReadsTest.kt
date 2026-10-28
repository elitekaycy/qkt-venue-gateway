package com.qkt.venuegateway.support

import com.qkt.venuegateway.support.JsonReads.bool
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.long
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.needDecimal
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JsonReadsTest {
    private val o =
        Json
            .parseToJsonElement(
                """{"q":"87717.60","n":83940.0,"e":1e-06,"empty":"","nil":null,"t":1700000000000.0,"b":"true",
               "list":[{"a":1}],"secret":"acct-123"}""",
            ).jsonObject

    @Test
    fun `decimals are exact from quoted and unquoted text`() {
        assertThat(o.decimal("q")!!.toPlainString()).isEqualTo("87717.60")
        assertThat(o.decimal("n")!!.toPlainString()).isEqualTo("83940.0")
        assertThat(o.decimal("e")).isEqualByComparingTo("0.000001")
        assertThat(o.long("t")).isEqualTo(1_700_000_000_000)
        assertThat(o.bool("b")).isTrue
    }

    @Test
    fun `empty strings and nulls read as absent`() {
        assertThat(o.text("empty")).isNull()
        assertThat(o.text("nil")).isNull()
        assertThat(o.decimal("missing")).isNull()
        assertThat(o.objects("nil")).isEmpty()
        assertThat(o.objects("list")).hasSize(1)
    }

    @Test
    fun `a needed field names itself and the keys present, never their values`() {
        assertThatThrownBy {
            o.need(
                "absent",
            )
        }.hasMessageContaining("field absent missing").hasMessageNotContaining("acct-123")
        assertThatThrownBy { o.needDecimal("empty") }.hasMessageContaining("field empty missing")
    }
}
