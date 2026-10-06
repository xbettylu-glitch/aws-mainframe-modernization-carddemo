#!/usr/bin/env bash
# Sensitivity check for the harness: builds scratch copies of the Java port with the cent
# truncation (RoundingMode.DOWN) replaced by rounding, and confirms the parity comparison
# against the saved COBOL baselines FAILS for each mutant. Exits 1 if any mutant goes undetected.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$HERE/.." && pwd)"
SCRATCH="$(mktemp -d)"; trap 'rm -rf "$SCRATCH"' EXIT
undetected=0
for mode in HALF_UP HALF_EVEN UP CEILING FLOOR; do
  M="$SCRATCH/$mode"; mkdir -p "$M"
  cp -r "$SRC/pom.xml" "$SRC/src" "$SRC/parity" "$M/"
  rm -rf "$M/parity/out" "$M/parity/build"
  sed -i "s/RoundingMode.DOWN/RoundingMode.$mode/" "$M/src/main/java/com/carddemo/interest/InterestCalculator.java"
  (cd "$M" && mvn -q -B package -DskipTests) >/dev/null || { echo "build failed for $mode"; exit 2; }
  for scn in "$M"/parity/scenarios/*/; do
    "$M/parity/run-java.sh" "$scn" "$M/out/$(basename "$scn")" 2>/dev/null
  done
  mkdir -p "$M/base"; for scn in "$M"/parity/scenarios/*/; do ln -s "$scn/expected" "$M/base/$(basename "$scn")"; done
  if "$HERE/compare.py" "$M/base" "$M/out" > "$M/compare.log"; then
    echo "UNDETECTED  RoundingMode.$mode"; undetected=1
  else
    echo "DETECTED    RoundingMode.$mode: $(grep -c '^DIFF' "$M/compare.log") of $(ls "$M/base" | wc -l) scenarios differ"
  fi
done
exit $undetected
