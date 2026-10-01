#!/usr/bin/env bash
# The next release from the Conventional Commits since the last v* tag: bumps VERSION, prepends its
# section to CHANGELOG.md, writes the section alone to $NOTES (default release-notes.md) and prints the
# version. Prints nothing and changes nothing when no commit warrants a release. The first release (no
# tag yet) is the version already in VERSION. Breaking changes bump the minor version before 1.0.
set -euo pipefail

notes=${NOTES:-release-notes.md}
last=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
range=${last:+$last..}HEAD
types='feat|fix|perf|refactor|test|docs|build|ci|chore'
log=$(git log --no-merges --format='%h%x09%s%x09%b%x1e' "$range")

breaking=$(grep -cE "^[^	]+	($types)(\([^)]*\))?!:|BREAKING CHANGE:" <<<"$log" || true)
feats=$(grep -cE "^[^	]+	feat(\([^)]*\))?!?:" <<<"$log" || true)
fixes=$(grep -cE "^[^	]+	(fix|perf)(\([^)]*\))?!?:" <<<"$log" || true)

IFS=. read -r major minor patch <"VERSION"
if [[ -z $last ]]; then
  :
elif ((breaking > 0)); then
  if ((major == 0)); then minor=$((minor + 1)); patch=0; else major=$((major + 1)); minor=0; patch=0; fi
elif ((feats > 0)); then
  minor=$((minor + 1)); patch=0
elif ((fixes > 0)); then
  patch=$((patch + 1))
else
  exit 0
fi
version=$major.$minor.$patch

# One line per commit, "- scope: description (sha)", under the heading of its kind.
awk -v RS='\036' -F'\t' -v version="$version" -v day="$(date -u +%F)" '
  { sub(/^\n/, "") }
  $2 ~ /^[a-z]+(\([^)]*\))?!?: / {
    kind = $2; sub(/[(!:].*/, "", kind)
    scope = ""; if (match($2, /^[a-z]+\([^)]*\)/)) { scope = substr($2, RSTART, RLENGTH); sub(/^[a-z]+\(/, "", scope); sub(/\)$/, "", scope); scope = scope ": " }
    desc = $2; sub(/^[^:]*: /, "", desc)
    line = "- " scope desc " (" $1 ")"
    if ($2 ~ /^[a-z]+(\([^)]*\))?!:/ || $3 ~ /BREAKING CHANGE:/) out["Breaking changes"] = out["Breaking changes"] line "\n"
    if (kind == "feat") out["Features"] = out["Features"] line "\n"
    if (kind == "fix") out["Fixes"] = out["Fixes"] line "\n"
    if (kind == "perf") out["Performance"] = out["Performance"] line "\n"
  }
  END {
    printf "## v%s (%s)\n", version, day
    n = split("Breaking changes,Features,Fixes,Performance", order, ",")
    for (i = 1; i <= n; i++) if (order[i] in out) printf "\n### %s\n\n%s", order[i], out[order[i]]
  }' <<<"$log" >"$notes"

printf '%s\n' "$version" >VERSION
previous=
if [[ -f CHANGELOG.md ]]; then previous=$(sed -n '/^## /,$p' CHANGELOG.md); fi
{
  printf '# Changelog\n\nWritten at each release by scripts/release.sh from Conventional Commits; not edited by hand.\n\n'
  cat "$notes"
  [[ -n $previous ]] && printf '\n%s\n' "$previous"
} >CHANGELOG.md
echo "$version"
