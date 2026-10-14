# Running the gateway

Run one gateway per venue account, next to qkt. Each gateway has its own container, port, tokens and
volume. Everything about running one is split by task:

| I want to | Read |
|---|---|
| Start a gateway with Compose, Docker, or from source | [Run a gateway](get-started/run-a-gateway.md) |
| Look up an environment variable or pass a secret as a file | [Configuration](reference/configuration.md) |
| Connect qkt to it | [Connect qkt](connect/qkt.md) |
| Stop new exposure from outside qkt | [Kill switch and guardrails](connect/guardrails.md) |
| See every route and its token | [API at a glance](reference/api.md) |
| Back up the state or upgrade | [State, backups and upgrades](operations/state-backups-upgrades.md) |
| Check readiness and decide what to alert on | [Health and logs](operations/health-and-logs.md) |
| Move from a test account to a real one | [Going live](get-started/going-live.md) |
| Fix an error message | [Troubleshooting](operations/troubleshooting.md) |

Venue-specific setup is on each adapter's page: [paper](adapters/paper.md), [Deribit](adapters/deribit.md)
and [Bybit](adapters/bybit.md).
