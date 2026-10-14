# Quickstart

You need Docker and [qkt](https://docs.tryqkt.com). The paper account trades on Deribit's live prices,
so no venue account is needed.

## 1. Start the paper gateway

```bash
git clone https://github.com/elitekaycy/qkt-venue-gateway
cd qkt-venue-gateway
cp .env.example .env
docker compose up -d
```

## 2. Check it is healthy

Give it a few seconds: `docker compose ps` shows `(healthy)` when it serves.

```bash
source .env
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" \
  http://127.0.0.1:8443/v1/health
```

You will see `"adapter":"paper"` and `"venue_connected":true`.

## 3. Point qkt at it

```bash
export PAPER_TRADER_TOKEN
cat > qkt.config.yaml <<'EOF'
brokers:
  paper:
    type: gateway
    gateway_url: http://127.0.0.1:8443
    api_key: env:PAPER_TRADER_TOKEN
    expected_adapter: paper
    expected_account_login: paper
    expected_trade_mode: demo
EOF
```

## 4. Send an order

```bash
cat > first.qkt <<'EOF'
STRATEGY first VERSION 1
SYMBOLS
    btc = PAPER:BTC_USDC_PERPETUAL EVERY 1s
RULES
    WHEN POSITION.btc = 0 AND TRADES.today = 0
    THEN BUY btc SIZING 0.001
EOF

qkt daemon --config qkt.config.yaml >qkt.log 2>&1 &
sleep 5
qkt deploy first.qkt --as first
sleep 5
curl -H "Authorization: Bearer $PAPER_TRADER_TOKEN" \
  http://127.0.0.1:8443/v1/positions
```

The order filled when `positions` lists `BTC_USDC-PERPETUAL` with `"quantity":"0.001"`. The fill itself is at
`/v1/deals?from=0&to=<now in ms>` (both bounds are required). Stop with `qkt stop first --flatten`, then
`qkt daemon stop`.

## Where next

- Run it the way you will keep it: [Run a gateway](run-a-gateway.md).
- Trade a real account: [Going live](going-live.md).
- Every command above in a recording: the clip on the [home page](../index.md#see-it-run).
