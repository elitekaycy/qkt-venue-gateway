#!/usr/bin/env bash
# Pull request rules (CLAUDE.md, docs/adapters.md): branch name, title, commits, adapter branch scope.
# Usage: check-pr.sh <base-sha> <head-sha> <branch> <title>
set -euo pipefail

base=${1:?base sha} head=${2:?head sha} branch=${3:?branch} title=${4:?title}
types='feat|fix|perf|refactor|test|docs|build|ci|chore'
subject_re='^('"$types"')(\([a-z0-9-]+\))?!?: [a-z0-9`].*[^.]$'
attribution_re='^co-authored-by:.*(claude|anthropic|copilot|openai|chatgpt|codex|cursor|gemini)|generated (with|by) .*(claude|copilot|chatgpt|codex|gemini| ai)|🤖'
fail=0
err() { echo "::error::$*"; fail=1; }

subject_ok() { [[ ${#1} -le 72 && $1 =~ $subject_re ]]; }

case $branch in
  dependabot/*) exit 0 ;;
  adapter/*) [[ $branch =~ ^adapter/[a-z][a-z0-9]*$ ]] || err "adapter branch must be adapter/<venue>: $branch" ;;
  claude/*) [[ $branch =~ ^claude/[A-Za-z0-9._-]+$ ]] || err "bad session branch name: $branch" ;;
  *) [[ $branch =~ ^($types)/[a-z0-9][a-z0-9._-]*$ ]] || err "branch must be <type>/<topic> or adapter/<venue>: $branch" ;;
esac

subject_ok "$title" || err "PR title must be a conventional subject starting lowercase, ≤ 72 chars: $title"

while IFS= read -r sha; do
  subject=$(git log -1 --format=%s "$sha")
  subject_ok "$subject" || err "commit ${sha:0:7} subject must be conventional, starting lowercase, ≤ 72 chars: $subject"
  if git log -1 --format=%B "$sha" | grep -iqE "$attribution_re"; then
    err "commit ${sha:0:7} carries tool or AI attribution; only a 'session(<type>): <link>' line is allowed"
  fi
done < <(git rev-list --no-merges "$base..$head")

changed=$(git diff --name-only "$base...$head")
for added in $(git diff --name-only --diff-filter=A "$base...$head" | grep -E '^adapter-[a-z0-9]+/build\.gradle\.kts$' || true); do
  m=${added%%/*}
  [[ $branch == "adapter/${m#adapter-}" ]] || err "a new adapter ($m) comes from branch adapter/${m#adapter-}"
done

if [[ $branch =~ ^adapter/([a-z0-9]+)$ ]]; then
  venue=${BASH_REMATCH[1]}
  allowed="^(adapter-$venue/|settings\.gradle\.kts$|app/build\.gradle\.kts$|README\.md$|docs/adapters\.md$|\.github/workflows/adapter\.yml$)"
  while IFS= read -r f; do
    [[ -z $f || $f =~ $allowed ]] || err "$f is outside an adapter branch's scope (docs/adapters.md, 'The branch')"
  done <<<"$changed"
fi

((fail == 0)) && echo "pr: ok"
exit "$fail"
