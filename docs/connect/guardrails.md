# Kill switch and guardrails

[qkt-guardrails](https://github.com/elitekaycy/qkt-guardrails) is an engine-independent equity watchdog.
It holds the **guardian token**: it can read everything, and it can stop new exposure. It cannot place
orders. The switch lives in the gateway, not in qkt, so it works while qkt is down.

```bash
G="Authorization: Bearer $GATEWAY_GUARDIAN_TOKEN"
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"all"}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill -d '{"scope":"symbols","symbols":["BTC_USDC-PERPETUAL"]}'
curl -H "$G" -X POST http://127.0.0.1:8443/v1/kill/release -d '{"scope":"all"}'
```

While the switch is on, only cancels, position closes and orders that reduce a position go through.
Everything else is `423 kill_switch`. The switch survives restarts.

Every change that moves it is a `kill` event on `/v1/stream`, carrying the switch as `/v1/health`
reports it, so qkt hears it flip in order with fills.

## Two tokens, two roles

| Token | Variable | Can |
|---|---|---|
| Trader | `GATEWAY_TRADER_TOKEN` | Read, place, change and cancel orders, close positions. qkt uses this |
| Guardian | `GATEWAY_GUARDIAN_TOKEN` | Read, and flip the kill switch. Guardrails uses this |

Give each its own long random string (`openssl rand -hex 32`). A trader token cannot flip the kill switch.
