# Changelog

Written at each release by scripts/release.sh from Conventional Commits; not edited by hand.

## v0.3.1 (2026-10-28)

### Fixes

- alpaca: report a coin buy's fee in the coin, as the venue filled it (#104) (b535880)

## v0.3.0 (2026-10-28)

### Features

- alpaca: trade US shares and coin pairs on an Alpaca account (#93) (884e93a)
- support: read rfc 3339 times and encode query values on request (#99) (edb9636)
- api: share socket, http, json and paging helpers for adapters (#87) (d73c78e)
- api: list equities with their category and trading mode (#85) (435122e)

### Fixes

- ci: count a release from the newest tag, not the nearest on dev (#100) (c342efd)
- paper: refuse deribit errors and publish the listener safely (#89) (89b021b)

## v0.2.1 (2026-10-19)

### Fixes

- deribit: list volume_step as min_trade_amount, not contract_size (#80) (862d191)
- host: default the deals and settlements window to now (#76) (010db95)

## v0.2.0 (2026-10-12)

### Features

- bybit: add the bybit adapter, linear and spot (#58) (b66223f)
- testkit: check a market order never leaves a remainder working (#51) (f87f1af)
- paper: keep recorded depth and open interest for a set window (#47) (65c4a10)
- deribit: keep recorded depth and open interest for a set window (#46) (51ec8cc)
- paper: record and serve deribit's order-book depth (#44) (0d6fe06)
- deribit: record and serve the order book's ten best levels (#43) (28a05d8)
- host: serve order-book depth as a declared capability (#42) (2c959ef)
- paper: serve deribit's tape and liquidations (#41) (1888c34)
- deribit: serve the public tape and its liquidations (#40) (5cf6d78)
- host: serve the public tape and liquidations by contract (#39) (2ef98e4)
- paper: declare option marks from deribit's option tickers (#36) (88d2750)
- deribit: declare option marks, carried on every option ticker (#35) (3af6ddb)
- api: declare option marks, the mark iv and forward on quotes (#34) (835a582)
- paper: serve deribit's open interest as it is recorded (#33) (6af5229)
- deribit: record and serve open interest from the ticker (#32) (e3d9a9c)
- host: serve open interest as a declared capability (#31) (6443341)
- paper: serve deribit's mark and index history (#30) (b7726a1)
- deribit: serve mark and index history from the trade tape (#29) (8363c01)
- host: serve mark and index history as the mark_prices capability (#28) (0cc12fc)
- deribit: settle expired futures and options from their history (#26) (9fa0d74)
- paper: charge held perpetuals deribit's published funding (#22) (e56163f)
- deribit: report funding and funding rates, refuse unlisted codes (#21) (1bc29ed)
- host: declare adapter capabilities and carry perpetual funding (#13) (4cfd216)
- wire: carry the venue's fee index on quotes (#12) (dd9525e)

### Fixes

- host: send kill switch changes on the event stream (#57) (9b728e8)
- deribit: cancel a market order's remainder, never let it rest (#52) (a5196e7)
- deribit: leave inactive contracts out of the listing (#38) (fe213e5)
- host: close stream and order listing gaps from the audit (#25) (130450c)
- host: make positions/close idempotent on a client order id (#24) (f8ace6e)
- host: look up unrecorded submits at the venue on a new journal (#23) (c6a8dce)
- host: stop refreshing quotes while the adapter's feed is down (#16) (ffbd44c)
- host: apply kill switch changes atomically, never print tokens (#17) (dd21980)
- host: reconcile venue history from the last full run (#15) (86e777a)
- host: keep order states forward and resolve lost submits safely (#14) (7788b4a)

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
