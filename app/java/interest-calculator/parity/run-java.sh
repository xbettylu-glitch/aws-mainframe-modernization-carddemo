#!/usr/bin/env bash
# Runs the Java port against one scenario (same inputs as run-cobol.sh).
# Usage: run-java.sh <scenario-dir> <output-dir>
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCN="$(cd "$1" && pwd)"; OUT="$2"
JAR="$(ls "$HERE"/../target/interest-calculator-*.jar | head -1)"
source "$HERE/scenario-env.sh" "$SCN"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
for f in tcatbal cardxref acctdata discgrp; do cp "$SCN/input/$f.txt" "$WORK/$f.txt"; done
for dd in $SCN_MISSING; do
  case "$dd" in
    TCATBALF) rm -f "$WORK/tcatbal.txt" ;; XREFFILE) rm -f "$WORK/cardxref.txt" ;;
    ACCTFILE) rm -f "$WORK/acctdata.txt" ;; DISCGRP) rm -f "$WORK/discgrp.txt" ;;
  esac
done
java -jar "$JAR" --parm "$SCN_PARM" --timestamp "$SCN_TIMESTAMP" \
  --tcatbal "$WORK/tcatbal.txt" --xref "$WORK/cardxref.txt" \
  --acct "$WORK/acctdata.txt" --discgrp "$WORK/discgrp.txt" \
  --transact "$WORK/transact.dat" > "$OUT/sysout.txt" 2> "$WORK/stderr.txt"
echo $? > "$OUT/rc"
if [ -f "$WORK/transact.dat" ]; then cp "$WORK/transact.dat" "$OUT/transact.dat"; else : > "$OUT/transact.dat"; fi
if [ -f "$WORK/acctdata.txt" ]; then cp "$WORK/acctdata.txt" "$OUT/acctdata.txt"; else : > "$OUT/acctdata.txt"; fi
[ -s "$WORK/stderr.txt" ] && { echo "--- java stderr ($SCN):" >&2; cat "$WORK/stderr.txt" >&2; }
exit 0
