#!/usr/bin/env bash
# Runs the original COBOL CBACT04C against one scenario.
# Usage: run-cobol.sh <scenario-dir> <output-dir>
# Outputs: sysout.txt, rc, transact.dat (TRANSACT), acctdata.txt (ACCTFILE unloaded after the run)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCN="$(cd "$1" && pwd)"; OUT="$2"
BIN="$HERE/build"
source "$HERE/scenario-env.sh" "$SCN"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
for f in tcatbal cardxref acctdata discgrp; do tr -d '\r' < "$SCN/input/$f.txt" > "$WORK/$f.txt"; done

export DD_TCATIN="$WORK/tcatbal.txt" DD_XREFIN="$WORK/cardxref.txt" \
       DD_ACCTIN="$WORK/acctdata.txt" DD_DISCIN="$WORK/discgrp.txt" \
       DD_TCATBALF="$WORK/tcatbal.ksds" DD_XREFFILE="$WORK/xref.ksds" \
       DD_ACCTFILE="$WORK/acct.ksds" DD_DISCGRP="$WORK/discgrp.ksds" \
       DD_TRANSACT="$WORK/transact.dat"
"$BIN/parload" > "$WORK/load.log" || { cat "$WORK/load.log"; exit 1; }
for dd in $SCN_MISSING; do
  case "$dd" in
    TCATBALF) rm -rf "$WORK"/tcatbal.ksds* ;; XREFFILE) rm -rf "$WORK"/xref.ksds* ;;
    ACCTFILE) rm -rf "$WORK"/acct.ksds* ;;   DISCGRP) rm -rf "$WORK"/discgrp.ksds* ;;
  esac
done

# LIVE_CLOCK=1: leave FUNCTION CURRENT-DATE on the real clock (timestamps then masked by compare.py)
[ "${LIVE_CLOCK:-0}" = 1 ] || export COB_CURRENT_DATE="$SCN_COB_DATE"
"$BIN/intcalc" "$SCN_PARM" > "$OUT/sysout.txt" 2> "$WORK/stderr.txt"
echo $? > "$OUT/rc"
if [ -f "$WORK/transact.dat" ]; then cp "$WORK/transact.dat" "$OUT/transact.dat"; else : > "$OUT/transact.dat"; fi
if [ -e "$WORK/acct.ksds" ]; then
  DD_UNLOUT="$OUT/acctdata.txt" "$BIN/parunld" ACCT
else
  : > "$OUT/acctdata.txt"
fi
