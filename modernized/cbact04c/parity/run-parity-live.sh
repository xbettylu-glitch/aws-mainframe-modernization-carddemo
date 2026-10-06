#!/usr/bin/env bash
# Parity on the real clock: runs the COBOL and the Java port on every scenario WITHOUT freezing
# FUNCTION CURRENT-DATE / --timestamp, then compares with the timestamp fields masked
# (compare.py --mask-timestamps). Does not touch the saved baselines. Run run-parity.sh first
# (or build-cobol.sh + mvn package) so both executables exist.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
export LIVE_CLOCK=1
rm -rf out/live; mkdir -p out/live/cobol out/live/java
for scn in scenarios/*/; do
  name="$(basename "$scn")"
  ./run-cobol.sh "$scn" "out/live/cobol/$name"
  ./run-java.sh "$scn" "out/live/java/$name"
done
./compare.py --mask-timestamps out/live/cobol out/live/java out/live/RESULTS.md
