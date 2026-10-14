# Documentation

The docs are published at **[venuegw.tryqkt.com](https://venuegw.tryqkt.com)**. These are the same pages,
in the order the site shows them.

| Section | Pages |
|---|---|
| Get started | [Quickstart](get-started/quickstart.md) · [Run a gateway](get-started/run-a-gateway.md) · [Going live](get-started/going-live.md) |
| Concepts | [How it works](concepts/how-it-works.md) · [What it guarantees](concepts/guarantees.md) · [Design](design.md) |
| Connect | [Connect qkt](connect/qkt.md) · [Kill switch and guardrails](connect/guardrails.md) |
| Adapters | [Overview](adapters/overview.md) · [Paper](adapters/paper.md) · [Deribit](adapters/deribit.md) · [Bybit](adapters/bybit.md) · [Adapter rules](adapters.md) · [Writing an adapter](writing-an-adapter.md) |
| Reference | [Configuration](reference/configuration.md) · [API at a glance](reference/api.md) · [Wire protocol](vgp-v1-wire.md) · [Ports and security](reference/ports-and-security.md) |
| Operations | [State, backups and upgrades](operations/state-backups-upgrades.md) · [Health and logs](operations/health-and-logs.md) · [Troubleshooting](operations/troubleshooting.md) |
| Project | [Changelog](../CHANGELOG.md) · [Contributing](../CONTRIBUTING.md) · [Security policy](../SECURITY.md) |

The adapter pages are the adapters' own READMEs (`adapter-*/README.md`), included into the site.

To build the site locally: `pip install -r requirements-docs.txt && mkdocs serve`.
