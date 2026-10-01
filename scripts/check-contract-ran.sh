#!/usr/bin/env bash
# Fails unless adapter-<venue>'s contract suite ran: present, with tests, none skipped. Run after its tests.
set -euo pipefail

venue=${1:?usage: check-contract-ran.sh <venue>}
shopt -s nullglob
reports=(adapter-"$venue"/build/test-results/test/TEST-*ContractTest.xml)
((${#reports[@]} > 0)) || { echo "::error::adapter-$venue: no contract test report"; exit 1; }
for r in "${reports[@]}"; do
  line=$(grep -m1 '<testsuite ' "$r")
  tests=$(sed -E 's/.* tests="([0-9]+)".*/\1/' <<<"$line")
  skipped=$(sed -E 's/.* skipped="([0-9]+)".*/\1/' <<<"$line")
  if ((tests == 0 || skipped > 0)); then
    echo "::error::$(basename "$r" .xml): $skipped of $tests skipped; the venue's test-environment secrets must be set"
    exit 1
  fi
  echo "$(basename "$r" .xml): $tests run"
done
