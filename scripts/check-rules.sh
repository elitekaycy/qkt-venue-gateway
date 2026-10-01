#!/usr/bin/env bash
# The repository rules the compiler and ktlint do not check (CLAUDE.md). Run from the repository root.
set -euo pipefail

fail=0
err() { echo "::error::$*"; fail=1; }

while IFS= read -r f; do
  limit=200
  [[ $f == */src/test/* ]] && limit=220
  n=$(wc -l <"$f")
  ((n <= limit)) || err "$f has $n lines (limit $limit)"
done < <(git ls-files '*.kt' '*.kts')

if hits=$(git grep -nE '\b(Double|Float)\b|\.to(Double|Float)\(' -- '*/src/main/*.kt'); then
  err "Double or Float in src/main (money and quantities are BigDecimal):"$'\n'"$hits"
fi

if hits=$(git ls-files | grep -E '(^|/)\.env$|\.db(-wal|-shm)?$|(^|/)state/'); then
  err "committed secrets or runtime state:"$'\n'"$hits"
fi

if hits=$(git grep -lE '^import com\.qkt\.(venuegateway\.(host|config)|vgp)\.' -- 'adapter-*/src/main/*.kt'); then
  err "an adapter imports host, wire or app code:"$'\n'"$hits"
fi

for dir in adapter-*/; do
  m=${dir%/}
  [[ $m == adapter-api || $m == adapter-testkit ]] && continue
  [[ -f $m/CLAUDE.md ]] || err "$m has no CLAUDE.md (docs/adapters.md)"
done

((fail == 0)) && echo "rules: ok"
exit "$fail"
