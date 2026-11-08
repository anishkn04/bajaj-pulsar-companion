#!/usr/bin/env bash
#
# Generates a Markdown changelog from conventional commits between the previous
# tag and the given tag. Used by the GitHub Release workflow.
#
# Usage: generate-changelog.sh <tag>
set -euo pipefail

TAG="${1:?tag required, e.g. v0.1.0}"

PREV=$(git describe --tags --abbrev=0 "${TAG}^" 2>/dev/null || true)
RANGE="${PREV:+${PREV}..}${TAG}"

if [[ -n "$PREV" ]]; then
    echo "## Changes since ${PREV}"
else
    echo "## First release"
fi
echo

declare -A sections=(
    [features]="## ✨ Features"
    [fixes]="## 🐛 Bug fixes"
    [docs]="## 📝 Documentation"
    [internal]="## 🔧 Internal"
    [other]="## 📦 Other"
)
declare -A bodies

while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^Merge ]] && continue

    type="${line%%(*}"
    type="${type%%: *}"
    body="${line#*: }"

    case "$type" in
        feat)   key="features"   ;;
        fix)    key="fixes"      ;;
        docs)   key="docs"       ;;
        refactor|perf|style) key="internal" ;;
        *)      key="other"      ;;
    esac

    bodies[$key]+="- ${body}"$'\n'
done < <(git log --pretty=format:'%s' "$RANGE" | tac)

for key in features fixes docs internal other; do
    if [[ -n "${bodies[$key]:-}" ]]; then
        echo "${sections[$key]}"
        echo
        printf '%s' "${bodies[$key]}"
        echo
    fi
done

echo "---"
echo "Assets: signed release APK and AAB built from tag ${TAG}."
