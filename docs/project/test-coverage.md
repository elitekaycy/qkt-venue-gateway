---
title: Test coverage
---

# Test coverage

Every line of production code runs under a test. Two gates hold that line in the `check` workflow's
`build` job:

| Gate | Tool | What fails it |
|---|---|---|
| Changed lines | `scripts/check-diff-coverage.sh` ([diff-cover](https://github.com/Bachmann1234/diff_cover) over Kover's merged XML) | Any line a pull request adds or edits under a module's `src/main` that no test executes. |
| Whole codebase | `koverVerify` (Kover), part of `./gradlew build` | Total line or branch coverage across all modules below `config/coverage-floor.properties`. |

The floor only goes up: a PR that raises total coverage raises the floor to the new whole-number
value in the same PR. The end state is `line=100` and `branch=100`.

## Running it locally

```bash
./gradlew build koverXmlReport koverHtmlReport   # report: build/reports/kover/html/index.html
./gradlew koverLog                               # total line coverage, printed
pip install -r config/coverage-requirements.txt
scripts/check-diff-coverage.sh origin/dev        # your branch's changed lines
```

## What counts

- The report merges every module's offline test run. Code that only a live test reaches
  (`AdapterContractTest`, probes) is uncovered: give it an offline test over a recorded fixture.
- Nothing is uploaded anywhere. Reports stay in the CI run's `coverage` artifact.
- The changed-lines gate is line-level; a changed `if` whose other arm never runs counts against
  the branch floor, so cover both arms.

## Exclusions

An annotation named `ExcludeFromCoverage` (any package) removes a declaration from the report. It is
for code only a running process reaches, such as `main` wiring real sockets and signal handlers,
never for code that is merely awkward to test. Every use states its reason.
