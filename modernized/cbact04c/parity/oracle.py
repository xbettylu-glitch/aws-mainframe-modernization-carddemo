#!/usr/bin/env python3
"""Independent reference model of CBACT04C ("legacy semantics" written from the spec).

Third implementation, separate from both the COBOL and the Java port: it re-derives every
TRANSACT record, the rewritten ACCTFILE, SYSOUT and the return code from a scenario's input
files using only the rules documented in ../README.md (Python Decimal, no shared code), then
diffs them against the GnuCOBOL baseline in scenarios/<name>/expected/ (or another output
root, e.g. the Java run in out/java/).

Usage: oracle.py [--against <out-root>] [scenario ...]
Exits 1 if any artifact differs.
"""
import argparse
import sys
from decimal import Decimal, ROUND_DOWN
from pathlib import Path

HERE = Path(__file__).resolve().parent
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"


def unzone(field: str, scale: int = 2) -> Decimal:
    """PIC S9(n)V99 DISPLAY with overpunched sign; a plain last digit means unsigned/positive."""
    last = field[-1]
    if last in POS:
        digits, sign = field[:-1] + str(POS.index(last)), 1
    elif last in NEG:
        digits, sign = field[:-1] + str(NEG.index(last)), -1
    else:
        digits, sign = field, 1
    return sign * Decimal(int(digits)).scaleb(-scale)


def store(value: Decimal, int_digits: int, scale: int = 2) -> Decimal:
    """COBOL store into S9(int_digits)V9(scale) without ROUNDED / ON SIZE ERROR:
    extra decimals are truncated, excess high-order digits are dropped, sign kept."""
    units = int((value * 10 ** scale).to_integral_value(ROUND_DOWN))
    sign = -1 if units < 0 else 1
    return sign * Decimal(abs(units) % 10 ** (int_digits + scale)).scaleb(-scale)


def zone(value: Decimal, int_digits: int, scale: int = 2) -> str:
    units = int(store(value, int_digits, scale) * 10 ** scale)
    digits = str(abs(units)).rjust(int_digits + scale, "0")
    return digits[:-1] + (NEG if units < 0 else POS)[int(digits[-1])]


def db2_timestamp(iso: str) -> str:
    """FUNCTION CURRENT-DATE reformatted by Z-GET-DB2-FORMAT-TIMESTAMP: YYYY-MM-DD-HH.MM.SS.hh0000"""
    date, time = iso.split("T")
    hms, hund = (time.split(".") + ["00"])[:2]
    return f"{date}-{hms.replace(':', '.')}.{hund[:2].ljust(2, '0')}0000"


class Abend(Exception):
    pass


def run(scn: Path):
    """Returns (transact bytes, acctdata text, sysout text, rc) for one scenario."""
    parm = (scn / "parm").read_text().strip("\n") if (scn / "parm").exists() else "2022071800"
    ts = (scn / "timestamp").read_text().strip() if (scn / "timestamp").exists() else "2022-07-18T01:02:03.45"
    missing = (scn / "missing").read_text().split() if (scn / "missing").exists() else []
    read = lambda f: [l.rstrip("\r") for l in (scn / "input" / f).read_text().split("\n") if l.rstrip("\r")]
    tcats = sorted(read("tcatbal.txt"), key=lambda r: r[:17])          # KSDS read in key order
    xref = {}
    for r in read("cardxref.txt"):
        xref.setdefault(r[25:36], r)                                     # alternate key FD-XREF-ACCT-ID
    disc = {r[:16]: r for r in read("discgrp.txt")}
    accts_in = read("acctdata.txt")
    accts = {r[:11]: r for r in accts_in}
    order = [r[:11] for r in accts_in]

    out, tx = ["START OF EXECUTION OF PROGRAM CBACT04C"], []
    stamp = db2_timestamp(ts)
    tran_id_prefix = parm[:10].ljust(10)

    def fail(msg, status):
        out.extend([msg, f"FILE STATUS IS: NNNN00{status}", "ABENDING PROGRAM"])
        raise Abend()

    def update(acct, total):                                             # 1050-UPDATE-ACCOUNT
        r = accts[acct]
        bal = store(unzone(r[12:24]) + total, 10)
        accts[acct] = r[:12] + zone(bal, 10) + r[24:78] + zone(Decimal(0), 10) * 2 + r[102:]

    rc = 0
    try:
        for dd, msg in (("TCATBALF", "ERROR OPENING TRANSACTION CATEGORY BALANCE"),
                        ("XREFFILE", "ERROR OPENING CROSS REF FILE35"),
                        ("DISCGRP", "ERROR OPENING DALY REJECTS FILE"),
                        ("ACCTFILE", "ERROR OPENING ACCOUNT MASTER FILE")):
            if dd in missing:
                fail(msg, "35")
        last, total, acct, card, suffix = None, Decimal(0), None, None, 0
        for rec in tcats:
            out.append(rec)
            acct_id, typ, cat = rec[:11], rec[11:13], rec[13:17]
            if acct_id != last:                                          # control break
                if last is not None:
                    update(acct, total)
                total, last = Decimal(0), acct_id
                if acct_id not in accts:
                    out.append(f"ACCOUNT NOT FOUND: {acct_id}")
                    fail("ERROR READING ACCOUNT FILE", "23")
                acct = acct_id
                if acct_id not in xref:
                    out.append(f"ACCOUNT NOT FOUND: {acct_id}")
                    fail("ERROR READING XREF FILE", "23")
                card = xref[acct_id][:16]
            group = accts[acct][112:122]
            d = disc.get(group + typ + cat)
            if d is None:
                out += ["DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE"]
                d = disc.get("DEFAULT".ljust(10) + typ + cat)
                if d is None:
                    fail("ERROR READING DEFAULT DISCLOSURE GROUP", "23")
            rate = unzone(d[16:22])
            if rate != 0:                                                # 1300-COMPUTE-INTEREST
                monthly = store(unzone(rec[17:28]) * rate / 1200, 9)
                total = store(total + monthly, 9)
                suffix = (suffix + 1) % 10 ** 6
                tx.append(
                    (tran_id_prefix + f"{suffix:06d}") + "01" + "0005" + "System".ljust(10)
                    + f"Int. for a/c {acct}".ljust(100) + zone(monthly, 9) + "0" * 9
                    + " " * 110 + card + stamp + stamp + " " * 20)
        # end of file: the loop exits without a final 1050-UPDATE-ACCOUNT (last account not rewritten)
        out.append("END OF EXECUTION OF PROGRAM CBACT04C")
    except Abend:
        rc = 231
    acct_out = "" if "ACCTFILE" in missing else "".join(accts[a] + "\n" for a in order)
    return "".join(tx).encode("latin-1"), acct_out, "".join(l + "\n" for l in out), f"{rc}\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--against", type=Path, help="output root with one dir per scenario (default: expected/)")
    ap.add_argument("scenarios", nargs="*")
    args = ap.parse_args()
    names = args.scenarios or sorted(p.name for p in (HERE / "scenarios").iterdir() if p.is_dir())
    failed = False
    for name in names:
        scn = HERE / "scenarios" / name
        got = args.against / name if args.against else scn / "expected"
        transact, acct, sysout, rc = run(scn)
        bad = []
        for art, mine, n in (("transact.dat", transact, 350), ("acctdata.txt", acct.encode(), None),
                             ("sysout.txt", sysout.encode(), None), ("rc", rc.encode(), None)):
            theirs = (got / art).read_bytes()
            if mine != theirs:
                split = (lambda b: [b[i:i + n] for i in range(0, len(b), n)]) if n else (lambda b: b.split(b"\n"))
                a, b = split(mine), split(theirs)
                i = next((i for i in range(max(len(a), len(b))) if (a[i:i + 1] != b[i:i + 1])), 0)
                bad.append(f"  {art} record {i + 1}:\n    oracle={a[i:i + 1]!r}\n    actual={b[i:i + 1]!r}")
        failed |= bool(bad)
        print(f"{'MATCH' if not bad else 'DIFF':5} {name} ({len(transact) // 350} transactions)")
        print("\n".join(bad)) if bad else None
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
