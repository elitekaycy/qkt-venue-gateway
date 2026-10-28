#!/usr/bin/env bash
# Checks that adapter-<venue> carries everything docs/adapters.md requires. Usage: check-adapter.sh <venue>
set -euo pipefail

venue=${1:?usage: check-adapter.sh <venue>}
[[ $venue =~ ^[a-z][a-z0-9]*$ ]] || { echo "::error::venue '$venue' must be lowercase letters and digits"; exit 1; }
m=adapter-$venue
pkg=$m/src/main/kotlin/com/qkt/venuegateway/$venue
services=$m/src/main/resources/META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory
fail=0
err() { echo "::error::$m: $*"; fail=1; }
need() { [[ -e $1 ]] || err "missing $1"; }

[[ -d $m ]] || { echo "::error::no module $m"; exit 1; }
need "$m/build.gradle.kts"
need "$m/README.md"
need "$m/CLAUDE.md"
need "$pkg"
need "$services"

if [[ -f $m/build.gradle.kts ]]; then
  grep -q 'project(":adapter-api")' "$m/build.gradle.kts" || err "build.gradle.kts must depend on :adapter-api"
  grep -q 'project(":adapter-testkit")' "$m/build.gradle.kts" || err "build.gradle.kts must test with :adapter-testkit"
  if grep -nE 'project\(":(host|vgp-wire|app)"\)' "$m/build.gradle.kts"; then err "depends on host, vgp-wire or app"; fi
fi

if [[ -f $services ]]; then
  while IFS= read -r cls; do
    [[ -z $cls || $cls == \#* ]] && continue
    file=$m/src/main/kotlin/$(tr . / <<<"$cls").kt
    [[ -f $file ]] || err "service names $cls but $file does not exist"
    grep -q "override val type = \"$venue\"" "$file" 2>/dev/null || err "$cls must declare type \"$venue\""
  done <"$services"
fi

git grep -qlE ':\s*AdapterContractTest\(\)' -- "$m/src/test" || err "no test extends AdapterContractTest"

# A venue-free adapter (no client/ of its own, as paper reads Deribit's) must build on the adapter whose client it
# reuses; every other adapter speaks its venue in client/ and carries fixtures recorded from it.
if [[ ! -d $pkg/client ]] && grep -qE 'project\(":adapter-[a-z0-9]+"\)' <(grep -vE 'adapter-(api|testkit)' "$m/build.gradle.kts"); then
  :
else
  need "$pkg/client"
  [[ -n $(find "$m/src/test/resources/fixtures" -type f 2>/dev/null | head -1) ]] ||
    err "no recorded fixtures in $m/src/test/resources/fixtures"
  if git grep -n 'import com\.qkt\.venuegateway\.adapter\.' -- "$pkg/client"; then
    err "client/ must not use adapter-api types; mapping is the only bridge"
  fi
fi

# Another adapter is reused only through its client/ (its venue's protocol) or market/ (its public market as
# adapter-api types), as paper simulates on Deribit's public prices: never its adapter, account mapping or settings.
for dir in adapter-*/; do
  other=${dir%/}
  other=${other#adapter-}
  [[ $other == "$venue" || $other == api || $other == testkit ]] && continue
  [[ -d $dir/src/main/kotlin/com/qkt/venuegateway/$other ]] || continue
  if grep -rnE "^import com\.qkt\.venuegateway\.$other\." "$m/src" | grep -vE "venuegateway\.$other\.(client|market)\."; then
    err "imports adapter-$other outside its client/ and market/ packages"
  fi
done

# Nothing outside the module names it: settings.gradle.kts discovers it, app bundles it, the README table is
# generated from its README's Venue line (scripts/adapter-index.sh) and CI reads its <VENUE>_CLIENT_* secrets by name.
grep -qE '^> Venue: .+ · Status: .+$' "$m/README.md" || err "README.md needs a '> Venue: <what it trades> · Status: <status>' line"

((fail == 0)) && echo "$m: complete"
exit "$fail"
