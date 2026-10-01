#!/usr/bin/env bash
# Prints, as a JSON array, the venues whose adapter a change touches. Usage: adapter-venues.sh [<base> <head> <branch>]
# With no arguments (nightly, manual) every adapter. A change to adapter-api or adapter-testkit touches them all;
# a Markdown-only change touches none.
set -euo pipefail

all=$(find . -maxdepth 1 -type d -name 'adapter-*' ! -name adapter-api ! -name adapter-testkit | sed 's#^\./adapter-##' | sort)
if (($# == 0)); then
  venues=$all
else
  base=$1 head=$2 branch=$3
  changed=$(git diff --name-only "$base...$head" | grep -v '\.md$' || true)
  if grep -qE '^(adapter-api|adapter-testkit)/' <<<"$changed"; then
    venues=$all
  else
    venues=$( { grep -oE '^adapter-[a-z0-9]+/' <<<"$changed" | sed 's#^adapter-##; s#/$##' || true
               [[ $branch =~ ^adapter/([a-z0-9]+)$ ]] && echo "${BASH_REMATCH[1]}" || true; } | sort -u)
  fi
fi
jq -cn --arg v "$venues" '$v | split("\n") | map(select(length > 0))'
