# What it guarantees

The gateway makes the same promises for every venue. They live in the host, so an adapter cannot weaken them.
This page states each one and says how it is kept. The full reasoning is in [Design](../design.md).

## Orders are placed at most once

- A `POST /v1/orders` with a known `client_order_id` and the same body returns the stored order. A
  different body is `409 conflict`, and an id the gateway has marked dead is `409` for ever.
- A new id is first written to the journal as `pending` (the write-ahead record), then sent to the
  adapter, then updated.
- A crash between that record and the venue's answer is resolved on restart by looking the order up at
  the venue by its label: found means the venue has it, and its state and fills are journaled; not found
  after the venue link is up means it was never placed, and it is marked rejected.
- While the venue link is down the gateway answers `503`, and refuses submits before the write-ahead
  record, so a retry starts clean.

## Nothing is lost on restart

- Every order change, fill, settlement and funding record is journaled in one transaction with its dedupe
  row, then published to open streams. `seq` increases by exactly one per event.
- The journal is SQLite in WAL mode under the state directory, one file per account. Keep it on a volume.
- `GET /v1/stream?since=<seq>` replays every retained event with `seq > since`, then goes live. Retention
  keeps at least 7 days. A `since` older than the oldest retained event, or from another stream, gets a
  `reset` first, and qkt resynchronizes from REST.
- Losing the journal file means a new stream id, which tells every client to resynchronize. It does not
  place an order twice: the gateway looks up each submit it has no record of at the venue before placing it.

## Lost orders are recovered

- Reconciliation runs at start, after every venue reconnect, and every 60 seconds. Open orders are compared
  with the journal, and a journal order the venue no longer lists is looked up by label and its final state
  and fills journaled.
- Fills since the last reconciled fill (with a 5-minute overlap) are journaled through the fill dedupe, so a
  push lost during a disconnect becomes an event late but exactly once. Settlements and funding are
  reconciled the same way, when the adapter declares them.
- A `GET /v1/orders/{id}` that neither the journal nor the venue knows is `404`, and that id is then dead:
  a late `POST` of it can never place an order.
- Positions are not journaled. They are served live from the venue.

## A kill switch outside qkt

- A scope is `all`, or a set of symbols, held in the journal so a restart keeps it.
- While a scope covers a symbol, `POST` and `PATCH` on orders are `423 kill_switch`, unless the order is
  `reduce_only` and reduces the account's position at the venue (checked against live positions). Cancels
  always pass.
- It is enforced in the gateway, never in qkt, so the guardian can flip it while qkt is down. See
  [Kill switch and guardrails](../connect/guardrails.md).

## Quotes stay fresh while they hold

- One quote hub per process merges every client's quote subscription into one adapter subscription.
- Each subscribed quote is pushed on change and re-sent with `time` advanced at least every 5 seconds while
  it holds, so a quiet book stays fresh and a stopped venue feed goes stale. Quotes are never journaled
  and never replayed.
