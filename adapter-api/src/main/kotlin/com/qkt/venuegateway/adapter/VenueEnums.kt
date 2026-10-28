package com.qkt.venuegateway.adapter

/** Whether an account trades money ([REAL]) or a venue's test environment ([DEMO]). */
enum class TradeMode { DEMO, REAL }

/** What an instrument is: [EQUITY] is a share of a company, held outright (not a CFD on it). */
enum class InstrumentKind { SPOT, PERPETUAL, FUTURE, OPTION, EQUITY }

/**
 * What the account may do with an instrument the venue still lists: a delisted or halted share is [CLOSE_ONLY] or
 * [DISABLED], a share the venue will not lend is [LONG_ONLY]. qkt refuses opening orders the mode forbids itself.
 */
enum class Tradability { FULL, LONG_ONLY, SHORT_ONLY, CLOSE_ONLY, DISABLED }

/** How the venue holds positions: one net position per symbol, or one per ticket. */
enum class Accounting { NETTING, HEDGING }

/** An order's direction. */
enum class Side { BUY, SELL }

/** The order types VGP v1 carries. */
enum class OrderType { MARKET, LIMIT, STOP, STOP_LIMIT }

/** How long an order works. */
enum class TimeInForce { GTC, IOC, FOK, DAY }

/** Where an order is in its life; the last three are final. */
enum class OrderStatus { WORKING, FILLED, CANCELLED, REJECTED }

/** What a venue charged for: VGP v1's cost kinds. */
enum class CostKind { COMMISSION, EXCHANGE_FEE, DELIVERY_FEE, FUNDING, SWAP }
