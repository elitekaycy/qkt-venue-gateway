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

# paper is venue-free: it reads Deribit through adapter-deribit's client and fixtures (adapter-paper/README.md).
if [[ $venue != paper ]]; then
  need "$pkg/client"
  [[ -n $(find "$m/src/test/resources/fixtures" -type f 2>/dev/null | head -1) ]] ||
    err "no recorded fixtures in $m/src/test/resources/fixtures"
  if git grep -n 'import com\.qkt\.venuegateway\.adapter\.' -- "$pkg/client"; then
    err "client/ must not use adapter-api types; mapping is the only bridge"
  fi
fi

grep -q "\"$m\"" settings.gradle.kts || err "not included in settings.gradle.kts"
grep -q "project(\":$m\")" app/build.gradle.kts || err "not bundled in app/build.gradle.kts"
grep -qE "^\| \`$venue\` \|" README.md || err "no row in the README adapter table"

((fail == 0)) && echo "$m: complete"
exit "$fail"
