# Changelog

Written at each release by scripts/release.sh from Conventional Commits; not edited by hand.

## v0.1.0 (2026-10-01)

### Features

- host: tell clients each position change (c9ef3ef)
- host: serve order changes and position closes (dbe7f06)
- deribit: build bars of any day-dividing window from deribit klines (891e283)
- host: keep expired dated contracts listed for 30 days and serve one by code (44336a6)
- deribit: add the deribit adapter, passing the contract suite on testnet (c6cdd40)
- deribit: map deribit orders, trades and positions onto adapter-api (8dcbd1b)
- deribit: add the private api client over the shared socket (029ab94)
- pass venue credentials to adapters as one login and secret pair (dc2cb66)
- testkit: add the adapter contract suite and run it on paper (6136077)
- app: gateway entry point with plugin adapter loading (d190da0)
- paper: paper adapter on deribit public data with settlement (797f253)
- paper: paper venue matching and netting ledger (d5d63b6)
- deribit: public ticker stream with resubscribe on reconnect (fe7d203)
- deribit: public client with exact decimal parsing (58827a4)
- host: quote hub with refresh and closed bars (82536a1)
- host: reconciler that heals the journal against the venue (efd423f)
- host: vgp server with auth, orders, deals, kill switch and stream (fdff8e4)
- host: idempotent order desk with write-ahead and kill switch (651e4e1)
- host: exact mapping between the wire and neutral orders (b9d95db)
- host: sqlite event journal with replay and dedupe (2b32ef1)
- api: vgp wire model and the venue adapter interface (0a505f4)

### Fixes

- host: tell positions even when settlement history fails (ff35b4e)
- host: settle a working order that ended at the venue while away (8926bf1)
- host: resolve a lost order from its fills when the venue forgot it (1821751)
- paper: renew the quote subscription so new expiries are quoted (a8599da)
- host: ask the adapter for one page of bars at a time (e13bc4e)
- deribit: fetch klines in spans under deribit's silent 5001 cap (068a4a6)
- host: journal an order state once when desk and push race (932539b)
- deribit: read the account summary as deribit sends it unextended (990c36e)
- host: drop orders and fills the gateway never placed (06acf6b)
- paper: acknowledge and save an order left working on arrival (abe08a2)
- paper: list deribit linear contracts in coins (f08e722)
