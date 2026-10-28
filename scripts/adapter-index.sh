#!/usr/bin/env bash
# Writes every list of adapters from the adapters themselves, so adding a venue edits nothing outside its
# adapter-<venue>/ directory by hand: the root README's table, the docs home table and pills, the adapters overview,
# the docs page that includes the adapter's README, and the docs navigation. Each adapter-<venue>/README.md starts with its title and one line:
#   # <Name> adapter
#   > Venue: <what it trades> · Status: <Stable|New|...>
# Usage: adapter-index.sh          rewrite them
#        adapter-index.sh --check  exit 1, naming the fix, when any is out of date
set -euo pipefail

check=false; [[ ${1:-} == --check ]] && check=true
stale=0
readme_rows="" docs_rows="" overview_rows="" pills="" nav="" pages=()
for dir in $(find . -maxdepth 1 -type d -name 'adapter-*' ! -name adapter-api ! -name adapter-testkit | sort); do
  m=${dir#./} venue=${m#adapter-}
  [[ -f $m/build.gradle.kts ]] || continue
  title=$(head -1 "$m/README.md" | sed -nE 's/^# (.+)$/\1/p')
  line=$(grep -m1 -E '^> Venue: .+ · Status: .+$' "$m/README.md") || true
  [[ -n $title && -n $line ]] ||
    { echo "::error::$m/README.md must start '# <Name> adapter' with a '> Venue: <what it trades> · Status: <status>' line" >&2; exit 1; }
  what=$(sed -E 's/^> Venue: (.+) · Status: (.+)$/\1/' <<<"$line")
  status=$(sed -E 's/^> Venue: (.+) · Status: (.+)$/\2/' <<<"$line")
  readme_rows+="| \`$venue\` | $what | $status. [Setup]($m/README.md) |"$'\n'
  docs_rows+="| [\`$venue\`](adapters/$venue.md) | $what | $status |"$'\n'
  overview_rows+="| [${title% adapter}]($venue.md) | \`$venue\` | $what | $status |"$'\n'
  pills+="  <span class=\"pill\">${title% adapter}</span>"$'\n'
  nav+="      - ${title% adapter}: adapters/$venue.md"$'\n'
  pages+=("$venue")
  page=$'---\ntitle: '"$title"$'\n---\n\n{% include-markdown "../../'"$m"$'/README.md" %}\n'
  if $check; then
    [[ -f docs/adapters/$venue.md && "$(cat "docs/adapters/$venue.md")"$'\n' == "$page" ]] || stale=1
  else
    printf '%s' "$page" > "docs/adapters/$venue.md"
  fi
done

# Replaces what lies between the `start` and `end` marker lines of $1 with $2.
splice() {
  local file=$1 body=$2 start=$3 end=$4
  local current; current=$(awk -v s="$start" -v e="$end" 'index($0,s){f=1;next} index($0,e){f=0} f' "$file")
  if $check; then
    [[ "$current"$'\n' == "$body" ]] || stale=1
    return
  fi
  BODY=$body awk -v s="$start" -v e="$end" 'index($0,s){print; printf "%s", ENVIRON["BODY"]; skip=1; next} index($0,e){skip=0} !skip' "$file" > "$file.tmp"
  mv "$file.tmp" "$file"
}
splice README.md $'| Adapter | Venue | Status |\n| --- | --- | --- |\n'"$readme_rows" '<!-- adapters:start -->' '<!-- adapters:end -->'
splice docs/index.md $'| Adapter | Venue | Status |\n|---|---|---|\n'"$docs_rows" '<!-- adapters:start -->' '<!-- adapters:end -->'
splice docs/index.md "$pills" '<!-- pills:start -->' '<!-- pills:end -->'
splice docs/adapters/overview.md $'| Adapter | `GATEWAY_ADAPTER` | Venue | Status |\n|---|---|---|---|\n'"$overview_rows" '<!-- adapters:start -->' '<!-- adapters:end -->'
splice mkdocs.yml "$nav" '# adapters:start' '# adapters:end'

for f in docs/adapters/*.md; do
  v=$(basename "$f" .md)
  [[ $v == overview || " ${pages[*]} " == *" $v "* ]] && continue
  if $check; then stale=1; else rm "$f"; fi
done
if $check && ((stale)); then echo "::error::the adapter lists are out of date: run scripts/adapter-index.sh" >&2; exit 1; fi
exit 0
