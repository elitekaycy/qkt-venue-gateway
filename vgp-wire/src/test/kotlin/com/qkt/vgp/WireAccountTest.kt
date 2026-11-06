package com.qkt.vgp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireAccountTest {
    private val kill = WireKillSwitch(all = true, symbols = listOf("BTC-PERPETUAL"))

    @Test
    fun `the kill switch reads back, covering no symbol by default`() {
        val json = wireShape(kill, kill.copy(), kill.copy(all = false), WireKillSwitch(all = true))

        assertThat(json.keys).containsExactly("all", "symbols")
        assertThat(WireKillSwitch(all = false).symbols).isEmpty()
        refusesEmpty<WireKillSwitch>()
    }

    @Test
    fun `health reads back under its wire names and lists no capability by default`() {
        val health = WireHealth("vgp1", "paper", "1.2.0", "7", "demo", true, kill, 1_000L, "s-1", 5L, listOf("bars"))

        val json =
            wireShape(
                health,
                health.copy(),
                health.copy(protocol = "vgp2"),
                health.copy(adapter = "deribit"),
                health.copy(adapterVersion = "1.3.0"),
                health.copy(accountLogin = "8"),
                health.copy(tradeMode = "real"),
                health.copy(venueConnected = false),
                health.copy(killSwitch = WireKillSwitch(all = false)),
                health.copy(serverTime = 2_000L),
                health.copy(stream = "s-2"),
                health.copy(seq = 6L),
                health.copy(capabilities = emptyList()),
            )

        assertThat(json.keys).contains(
            "adapter_version",
            "account_login",
            "trade_mode",
            "venue_connected",
            "kill_switch",
            "server_time",
        )
        assertThat(WireHealth("vgp1", "paper", "1", "7", "demo", true, kill, 1L, "s", 0L).capabilities).isEmpty()
        refusesEmpty<WireHealth>()
    }

    @Test
    fun `an account reads back and leaves out the margins its venue does not report`() {
        val account = WireAccount("USDC", "100.5", "101", "1", "99.5", "2", "1.5")

        val json =
            wireShape(
                account,
                account.copy(),
                account.copy(currency = "BTC"),
                account.copy(balance = "1"),
                account.copy(equity = "2"),
                account.copy(marginUsed = "3"),
                account.copy(marginAvailable = "4"),
                account.copy(initialMargin = null),
                account.copy(maintenanceMargin = null),
            )

        assertThat(json.keys).contains("margin_used", "margin_available", "initial_margin", "maintenance_margin")
        assertThat(written(WireAccount("USDC", "1", "1", "0", "1")))
            .isEqualTo("""{"currency":"USDC","balance":"1","equity":"1","margin_used":"0","margin_available":"1"}""")
        refusesEmpty<WireAccount>()
    }

    @Test
    fun `an option reads back with its dated fields, and a perpetual leaves every one of them out`() {
        val option =
            WireInstrument(
                "BTC-9OCT26-82000-P",
                "option",
                "BTC",
                "1",
                "0.0005",
                "0.1",
                "0.1",
                1_791_532_800_000L,
                "82000",
                "put",
                "BTC",
                "crypto",
                "close_only",
            )

        val json =
            wireShape(
                option,
                option.copy(),
                option.copy(code = "ETH-9OCT26-2000-C"),
                option.copy(kind = "future"),
                option.copy(currency = "ETH"),
                option.copy(contractSize = "10"),
                option.copy(tickSize = "0.001"),
                option.copy(volumeStep = "1"),
                option.copy(volumeMin = "2"),
                option.copy(expiry = null),
                option.copy(strike = null),
                option.copy(right = null),
                option.copy(underlying = null),
                option.copy(category = null),
                option.copy(trading = null),
            )

        assertThat(json.keys).contains("contract_size", "tick_size", "volume_step", "volume_min")
        val perpetual = WireInstrument("BTC-PERPETUAL", "perpetual", "BTC", "10", "0.5", "10", "10")
        assertThat(written(perpetual)).doesNotContain("expiry", "strike", "right", "underlying", "category", "trading")
        refusesEmpty<WireInstrument>()
    }

    @Test
    fun `positions read back, a hedging ticket and its open time left out when absent`() {
        val row = WirePosition("BTC-PERPETUAL", "-0.5", "84000", "t-1", 1_000L)
        val positions = WirePositions("hedging", listOf(row))

        val json =
            wireShape(
                row,
                row.copy(),
                row.copy(symbol = "ETH-PERPETUAL"),
                row.copy(quantity = "0.5"),
                row.copy(avgPrice = "83000"),
                row.copy(ticket = null),
                row.copy(openedAt = null),
            )
        wireShape(
            positions,
            positions.copy(),
            positions.copy(accounting = "netting"),
            positions.copy(positions = emptyList()),
        )

        assertThat(json.keys).contains("avg_price", "opened_at")
        assertThat(written(WirePosition("X", "1", "2"))).isEqualTo("""{"symbol":"X","quantity":"1","avg_price":"2"}""")
        refusesEmpty<WirePosition>()
        refusesEmpty<WirePositions>()
    }
}
