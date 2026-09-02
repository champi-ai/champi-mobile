#!/usr/bin/env bash
# Apply round:1 / round:2 labels to champi-mobile issues. Requires gh CLI with issues:write.
set -euo pipefail
REPO="${REPO:-champi-ai/champi-mobile}"

gh label create 'round:1' --repo "$REPO" --color 0e8a16 --description 'Thin-client MVP: build first' --force
gh label create 'round:2' --repo "$REPO" --color c5def5 --description 'Deferred: polish, offline, public-release scope' --force

ROUND1=(3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 19 20 21 22 23 24 25 28 29 30 31 38 39 43 44 46)
ROUND2=(18 26 27 32 33 34 35 36 37 40 41 42 45 47 48 49 50 51 52 53 54 55 56 57 58 59 60)

for n in "${ROUND1[@]}"; do gh issue edit "$n" --repo "$REPO" --add-label 'round:1'; done
for n in "${ROUND2[@]}"; do gh issue edit "$n" --repo "$REPO" --add-label 'round:2'; done
echo "Labelled ${#ROUND1[@]} round:1 and ${#ROUND2[@]} round:2 issues."
