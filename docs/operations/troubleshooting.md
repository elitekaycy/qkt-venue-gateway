# Troubleshooting

| Message or symptom | Fix |
|---|---|
| `GATEWAY_TRADER_TOKEN (or GATEWAY_TRADER_TOKEN_FILE) is required` | Set both tokens |
| `adapter deribit: setting environment is required` | Set `GATEWAY_SETTING_ENVIRONMENT` to `testnet` or `mainnet`. Bybit also needs `GATEWAY_SETTING_CATEGORY` (`linear` or `spot`) |
| `GATEWAY_SECRET is required with GATEWAY_LOGIN` | Set both credentials, or neither |
| `no adapter of type 'x' is installed` | Check `GATEWAY_ADAPTER`, or put the plugin jar in `GATEWAY_PLUGINS_DIR` |
| `takes no arguments` | Config files are gone: use `GATEWAY_*` variables |
| `401` on every call | Wrong token for this gateway |
| `423 kill_switch` | The kill switch is on; release it with the guardian token ([how](../connect/guardrails.md)) |
| `503 venue_unavailable` | The venue link is down; the gateway reconnects on its own |
| `501 unsupported` | The adapter does not declare the capability behind that route; see its page |
| qkt won't start: adapter, login or mode mismatch | The broker entry points at the wrong gateway, or `expected_*` is wrong ([Connect qkt](../connect/qkt.md)) |
