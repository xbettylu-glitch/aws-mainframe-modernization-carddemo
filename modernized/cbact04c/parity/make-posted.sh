#!/usr/bin/env bash
# Builds scenarios/sample-posted/input: the app/data/ASCII sample after the
# POSTTRAN step (original CBTRN02C, run with GnuCOBOL) has posted
# dailytran.txt. This is the state INTCALC sees in the normal job order.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$(cd "$HERE/../../../app/data/ASCII" && pwd)"
BIN="$HERE/build"; DEST="$HERE/scenarios/sample-posted/input"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
for f in tcatbal cardxref acctdata discgrp; do tr -d '\r' < "$DATA/$f.txt" > "$WORK/$f.txt"; done
tr -d '\r\n' < "$DATA/dailytran.txt" > "$WORK/dalytran.dat"   # RECFM=F, LRECL=350
export DD_TCATIN="$WORK/tcatbal.txt" DD_XREFIN="$WORK/cardxref.txt" \
       DD_ACCTIN="$WORK/acctdata.txt" DD_DISCIN="$WORK/discgrp.txt" \
       DD_TCATBALF="$WORK/tcatbal.ksds" DD_XREFFILE="$WORK/xref.ksds" \
       DD_ACCTFILE="$WORK/acct.ksds" DD_DISCGRP="$WORK/discgrp.ksds" \
       DD_DALYTRAN="$WORK/dalytran.dat" DD_TRANFILE="$WORK/transact.ksds" \
       DD_DALYREJS="$WORK/dalyrejs.dat"
"$BIN/parload" > /dev/null
COB_CURRENT_DATE="2022/07/18 00:00:00.00" "$BIN/posttran" | grep -E 'TRANSACTIONS|ERROR|ABEND' || true
mkdir -p "$DEST"
DD_UNLOUT="$DEST/acctdata.txt" "$BIN/parunld" ACCT
DD_UNLOUT="$DEST/tcatbal.txt" "$BIN/parunld" TCAT
cp "$WORK/cardxref.txt" "$WORK/discgrp.txt" "$DEST/"
echo "sample-posted: $(wc -l < "$DEST/tcatbal.txt") category balances, $(wc -l < "$DEST/acctdata.txt") accounts"
