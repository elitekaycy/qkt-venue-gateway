package com.qkt.venued.host.server

/** Who a token belongs to: qkt trades as [TRADER]; guardrails watches and flips the kill switch as [GUARDIAN]. */
enum class Role(
    val key: String,
) {
    TRADER("trader"),
    GUARDIAN("guardian"),
}
