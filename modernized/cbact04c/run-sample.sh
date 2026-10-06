#!/usr/bin/env bash
# Runs the modernized CBACT04C on the CardDemo sample extract in app/data/ASCII
# (the ASCII unload of the TCATBALF, CARDXREF, ACCTDATA and DISCGRP VSAM files).
# Usage: run-sample.sh [output-dir] [PARM] [timestamp]
#   output-dir defaults to ./sample-out; ACCTFILE is updated in a copy, never in app/data.
#   Omit the timestamp to use the real clock, as on the mainframe.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$(cd "$HERE/../../app/data/ASCII" && pwd)"
OUT="${1:-$HERE/sample-out}"; PARM="${2:-2022071800}"; TS="${3:-}"
JAR="$(ls "$HERE"/target/interest-calculator-*.jar 2>/dev/null | head -1 || true)"
[ -n "$JAR" ] || { (cd "$HERE" && mvn -q -B package -DskipTests); JAR="$(ls "$HERE"/target/interest-calculator-*.jar | head -1)"; }
mkdir -p "$OUT"
for f in tcatbal cardxref acctdata discgrp; do tr -d '\r' < "$DATA/$f.txt" > "$OUT/$f.txt"; done
cp "$OUT/acctdata.txt" "$OUT/acctdata.before.txt"
set +e
java -jar "$JAR" --parm "$PARM" ${TS:+--timestamp "$TS"} \
  --tcatbal "$OUT/tcatbal.txt" --xref "$OUT/cardxref.txt" --acct "$OUT/acctdata.txt" \
  --discgrp "$OUT/discgrp.txt" --transact "$OUT/transact.dat" > "$OUT/sysout.txt"
rc=$?
set -e
echo "rc=$rc  transactions=$(( $(stat -c %s "$OUT/transact.dat" 2>/dev/null || echo 0) / 350 ))  output in $OUT"
echo "sysout: $OUT/sysout.txt   TRANSACT: $OUT/transact.dat   ACCTFILE after: $OUT/acctdata.txt (before: acctdata.before.txt)"
exit $rc
