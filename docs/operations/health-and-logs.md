# Health and logs

## Health

`GET /v1/health` is the readiness check, and the image's healthcheck calls it. It reports the adapter, the
account, whether the venue link is up (`venue_connected`), the kill switch, the capabilities the adapter
declares, and the event `stream` and latest `seq`.

```bash
curl -H "Authorization: Bearer $GATEWAY_TRADER_TOKEN" http://127.0.0.1:8443/v1/health
```

With Compose, `docker compose ps` shows `(healthy)` once it serves.

## Alerts

Alert if `venue_connected` stays `false` for more than two minutes. The gateway reconnects on its own and
reconciles with the venue after every reconnect, so a short outage heals itself.

## Logs

Logs go to stdout and never contain tokens or secrets.

```bash
docker compose logs -f gateway-paper
```
