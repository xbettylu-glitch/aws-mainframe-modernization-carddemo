#!/usr/bin/env bash
# Full parity run:
#   1. compile the original COBOL with GnuCOBOL (build-cobol.sh)
#   2. run it on every scenario; outputs become the baseline in scenarios/<name>/expected/
#      (these baselines are also checked by the Java unit test GoldenParityTest)
#   3. build the Java port and run it on the same scenarios (out/java/<name>/)
#   4. compare record by record and write RESULTS.md
# Requires: cobc (GnuCOBOL 3.x with indexed-file support), Java 17, Maven, python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
./build-cobol.sh
./make-posted.sh
./gen-scenarios.py > /dev/null
(cd .. && mvn -q -B package -DskipTests)
rm -rf out; mkdir -p out/cobol out/java
for scn in scenarios/*/; do
  name="$(basename "$scn")"
  rm -rf "$scn/expected"
  ./run-cobol.sh "$scn" "$scn/expected"
  ln -s "../../$scn/expected" "out/cobol/$name"
  ./run-java.sh "$scn" "out/java/$name"
done
./compare.py out/cobol out/java RESULTS.md
