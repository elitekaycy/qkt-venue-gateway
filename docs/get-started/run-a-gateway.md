# Run a gateway

Run one gateway per venue account, next to qkt. Each gateway has its own container, port, tokens and
volume. Venue-specific setup is on each adapter's page: [paper](../adapters/paper.md),
[Deribit](../adapters/deribit.md) and [Bybit](../adapters/bybit.md).

## Docker Compose (recommended)

```bash
cp .env.example .env              # set the tokens: any long random strings (openssl rand -hex 32)
docker compose up -d              # gateway-paper on 127.0.0.1:8443
docker compose ps                 # shows (healthy) once it serves
docker compose logs -f gateway-paper
```

`docker-compose.yml` has one service per account:

| Service | Start with | Serves on |
|---|---|---|
| `gateway-paper` | `docker compose up -d` | `127.0.0.1:8443` |
| `gateway-deribit` | `docker compose --profile deribit up -d` | `127.0.0.1:8444` |

To add an account, copy a service and give it its own name, port, volume and variables. Bybit has no
compose service yet: start it with `docker run`, as its [setup](../adapters/bybit.md#setup) shows.

## Docker

```bash
docker build -t qkt-venue-gateway .
docker run -d --name gw-paper --restart unless-stopped \
  -p 127.0.0.1:8443:8443 -v gw-paper:/data \
  -e GATEWAY_TRADER_TOKEN=… -e GATEWAY_GUARDIAN_TOKEN=… \
  qkt-venue-gateway
```

The image runs as a non-root user, keeps its state in the `/data` volume, and its healthcheck calls
`GET /v1/health`.

## Without Docker

Requires JDK 21.

```bash
./gradlew :app:installDist
GATEWAY_TRADER_TOKEN=… GATEWAY_GUARDIAN_TOKEN=… app/build/install/qkt-venue-gateway/bin/qkt-venue-gateway
```

To run from source while developing, use `./gradlew :app:run` with the same two variables set.

## Then

- Every variable: [Configuration](../reference/configuration.md).
- Point qkt at it: [Connect qkt](../connect/qkt.md).
- Keep it healthy: [Operations](../operations/index.md).
