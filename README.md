# qkt-venued

A gateway between qkt and a trading venue, speaking VGP v1 (`docs/vgp-v1-wire.md`). One process serves
one account: idempotent orders, an event journal qkt can replay after any restart, a kill switch
guardrails can flip, live quotes and closed bars. Venues plug in as adapters; the first are a paper
adapter on Deribit's public data and a Deribit adapter.

```
./gradlew build
./gradlew :app:run --args="config.yaml"
```
