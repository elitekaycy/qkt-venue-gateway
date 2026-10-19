# CME MDP market data: what a decoder must handle

**Status:** requirements for a future `adapter-cme` (design §10, item 4). No CME module exists yet; nothing
live depends on this. Recorded from issues #54 and #56, which surveyed CME's 12 free MBO FIX samples
(one trading day each, 2017-10-25). The decoder is built and tested against these rules, not against ES
alone: ES futures is the simplest product in the batch and passes a decoder that is wrong on the others.

## 1. Entry types (`MDEntryType`, tag 269)

Bid (`0`), offer (`1`) and trade (`2`) are not the whole enum. The decoder branches on every value below
and fails loudly, naming the value, on any other:

| 269 | Meaning | Where it goes |
|---|---|---|
| `0` / `1` | Bid / offer | the executable book |
| `2` | Trade | trades |
| `E` / `F` | Implied bid / implied offer (CME's Implied Book: prices synthesized from calendar-spread activity) | a separate implied-book view, **never** the executable top of book a fill simulator reads |
| `N` / `O` | Session high bid / session low offer | session statistics, not depth |
| `J` | Empty book (a channel reset after a dual component failure) | clears the book; not a price |
| `W` | Fixing price | a published fixing, not a quote |
| `e` | Electronic volume (case-sensitive, distinct from `E`) | cumulative session volume; carries size (271) and **no price (270)** |

Implied entries are not rare: on the GC futures sample, 397 book-side messages are `E`/`F` against 77 real
trades; 6E and GE show the same pattern. ES had none of these beyond `0`/`1`/`2`; the other 11 samples
(options and non-equity-index futures especially) had substantial counts.

## 2. Book entries with trade linkage and no size

Most incremental-refresh (`35=X`) book entries (`269=0`/`1`) carry price (270) and size (271). A minority
carry trade-execution linkage instead (`37`, `37706`, `37707`) and **no 271**, for example from
`xcme_md_es_fut.gz` (ESZ7):

```
279=0  269=1  48=23936  55=ESZ7  270=257950.0  37706=1  37707=5535661496  37=644490560830
```

Reading 271 off every bid/offer entry stores a null or zero size and silently corrupts the depth at that
price. The decoder detects the linkage tags on a book entry and handles that case explicitly; whether it
is the book-side effect of a trade (and belongs in trade reconstruction rather than a depth update) is
confirmed against CME's MDP 3.0 dictionary before any size is assumed. A bid or offer with neither a size
nor linkage tags is refused by name.

## 3. Fixtures

`adapter-cme`'s tests carry, as recorded fixtures (CLAUDE.md, "Tests"):

- the ESZ7 book entries with linkage tags and no size (§2);
- at least one product with heavy implied volume (GC or GE, not ES), covering `E`/`F`, and samples of
  `N`, `O`, `J`, `W` and `e` (§1).

## Sources

- CME MDP 3.0, Implied Book: https://cmegroupclientsite.atlassian.net/wiki/display/EPICSANDBOX/MDP+3.0+-+Implied+Book
- CME MDP 3.0, Electronic Volume: https://cmegroupclientsite.atlassian.net/wiki/spaces/EPICSANDBOX/pages/457672472
- FIX dictionary, tag 269: https://www.onixs.biz/fix-dictionary/4.4/tagnum_269.html
