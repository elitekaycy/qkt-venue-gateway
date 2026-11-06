package com.qkt.vgp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One instrument the gateway lists; [expiry] only for dated contracts, [strike]/[right]/[underlying] only for options.
 * The fields after [trading] are an event contract's terms (`kind: event`), absent for every other kind; enum values
 * are lowercase names (`greater_or_equal`, `probability`).
 */
@Serializable
data class WireInstrument(
    val code: String,
    val kind: String,
    val currency: String,
    @SerialName("contract_size") val contractSize: String,
    @SerialName("tick_size") val tickSize: String,
    @SerialName("volume_step") val volumeStep: String,
    @SerialName("volume_min") val volumeMin: String,
    val expiry: Long? = null,
    val strike: String? = null,
    val right: String? = null,
    val underlying: String? = null,
    /** The venue's grouping of the instrument, absent when it has none. */
    val category: String? = null,
    /** `long_only`, `short_only`, `close_only` or `disabled`; absent means fully tradable. */
    val trading: String? = null,
    val series: String? = null,
    val event: String? = null,
    @SerialName("strike_type") val strikeType: String? = null,
    val floor: String? = null,
    val cap: String? = null,
    @SerialName("open_at") val openAt: Long? = null,
    @SerialName("close_at") val closeAt: Long? = null,
    @SerialName("occurs_at") val occursAt: Long? = null,
    val occasion: String? = null,
    @SerialName("can_close_early") val canCloseEarly: Boolean? = null,
    val exclusive: Boolean? = null,
    val frequency: String? = null,
    val title: String? = null,
    val outcome: String? = null,
    @SerialName("fee_model") val feeModel: String? = null,
    @SerialName("taker_fee_rate") val takerFeeRate: String? = null,
    @SerialName("maker_fee_rate") val makerFeeRate: String? = null,
    @SerialName("fee_rounding") val feeRounding: String? = null,
    /** The price grid where it changes across the price range; absent: [tickSize] everywhere. */
    @SerialName("tick_ranges") val tickRanges: List<WireTickRange>? = null,
)

/** Prices from [from] up to [to] move in steps of [tick]. */
@Serializable
data class WireTickRange(
    val from: String,
    val to: String,
    val tick: String,
)
