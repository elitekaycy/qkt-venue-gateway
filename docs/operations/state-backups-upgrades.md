# State, backups and upgrades

## State

The volume (`/data`) holds the account's journal: orders, fills, events and the kill switch. If it is lost,
the gateway starts a new stream, qkt resyncs from the venue, and the record of which order ids were
already used is gone. Until it restarts, the gateway looks up each submit it has no record of at the venue
(by label, else its fills of the last 7 days) before placing it, so a resend after the loss is answered,
not placed twice. Each new order costs those venue reads meanwhile.

Never share a volume between accounts or containers.

## Backup

Stop the container, then archive the volume (Compose prefixes volume names with the project name):

```bash
docker compose stop gateway-paper
docker run --rm -v qkt-venue-gateway_paper-state:/data -v "$PWD":/backup alpine \
  tar czf /backup/paper-state.tgz -C /data .
docker compose start gateway-paper
```

## Upgrade

Check out a release tag (the changes are in the [changelog](../project/changelog.md)), then run
`docker compose build && docker compose up -d`. The journal is kept, and qkt replays from its last
sequence.

For a gateway you started with `docker run`, build the new image and recreate the container with the
same volume and variables.
