#!/usr/bin/env bash
# Compiles the original CBACT04C (unmodified) plus the parity harness
# programs with GnuCOBOL. Output goes to parity/build/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/../../../app" && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"
# -fsign=EBCDIC: signed zoned decimals use the EBCDIC overpunch letters
# ({, A-I positive; }, J-R negative) that the ASCII sample data uses.
FLAGS=(-x -fsign=EBCDIC -I "$APP/cpy")
cobc "${FLAGS[@]}" -o "$OUT/intcalc" "$HERE/cobol/PARDRVR.cbl" "$APP/cbl/CBACT04C.cbl" "$HERE/cobol/cee3abd.c"
cobc "${FLAGS[@]}" -o "$OUT/posttran" "$APP/cbl/CBTRN02C.cbl" "$HERE/cobol/cee3abd.c"
cobc "${FLAGS[@]}" -o "$OUT/parload" "$HERE/cobol/PARLOAD.cbl"
cobc "${FLAGS[@]}" -o "$OUT/parunld" "$HERE/cobol/PARUNLD.cbl"
echo "built: $(ls "$OUT" | tr '\n' ' ')"
