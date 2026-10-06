#!/usr/bin/env python3
"""Checks that the documented CBACT04C quirks are present in a set of outputs.

Usage: check-quirks.py [--md] <out-root> [<out-root> ...]
Each root holds one directory per scenario (sysout.txt, rc, transact.dat, acctdata.txt), e.g.
scenarios/*/expected via out/cobol, or out/java. Inputs come from scenarios/<name>/input.
Quirks checked (all must hold in every root):
  truncation   TRAN-AMT = BAL * RATE / 1200 cut toward zero to the cent (no ROUNDED)
  zero-amount  a record is written whenever the rate is non-zero, even for 0.00 interest
  overflow     interest beyond 9 integer digits / balance beyond 10 lose high-order digits
  last-acct    the last account in TCATBALF key order is never rewritten
  category     TRAN-TYPE-CD = 01, TRAN-CAT-CD = 0005, TRAN-ID = PARM(10) + 6-digit counter
Exits 1 if any check fails.
"""
import sys
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP, getcontext
from pathlib import Path

getcontext().prec = 50
HERE = Path(__file__).resolve().parent
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"
CENT = Decimal("0.01")


def unzone(f):
    last = f[-1]
    if last in POS:
        return Decimal(int(f[:-1] + str(POS.index(last)))).scaleb(-2)
    if last in NEG:
        return -Decimal(int(f[:-1] + str(NEG.index(last)))).scaleb(-2)
    return Decimal(int(f)).scaleb(-2)


def wrap(v, int_digits):
    t = v.quantize(CENT, ROUND_DOWN)
    units = abs(int(t * 100)) % 10 ** (int_digits + 2)
    return (-1 if t < 0 else 1) * Decimal(units).scaleb(-2)


def lines(p):
    return [l.rstrip("\r") for l in p.read_text(encoding="latin-1").split("\n") if l.rstrip("\r")]


def check(scn, out, tally, fails):
    name = scn.name
    parm = (scn / "parm").read_text().strip("\n") if (scn / "parm").exists() else "2022071800"
    tcats = sorted(lines(scn / "input/tcatbal.txt"), key=lambda r: r[:17])
    accts_in = {r[:11]: r for r in lines(scn / "input/acctdata.txt")}
    disc = {r[:16]: unzone(r[16:22]) for r in lines(scn / "input/discgrp.txt")}
    raw = (out / "transact.dat").read_bytes().decode("latin-1")
    tx = [raw[i:i + 350] for i in range(0, len(raw), 350)]
    rc = (out / "rc").read_text().strip()
    accts_out = {r[:11]: r for r in lines(out / "acctdata.txt")} if (out / "acctdata.txt").stat().st_size else {}

    def fail(msg):
        fails.append(f"{out.parent.name}/{name}: {msg}")

    # category / type / TRAN-ID
    for i, t in enumerate(tx, 1):
        tally["records"] += 1
        if t[16:18] != "01" or t[18:22] != "0005":
            fail(f"record {i}: type/category {t[16:18]}/{t[18:22]}")
        else:
            tally["cat0005"] += 1
        if t[:16] != parm[:10].ljust(10) + f"{i % 10**6:06d}":
            fail(f"record {i}: TRAN-ID {t[:16]!r}")

    # pair each TRANSACT record with the category balance that produced it
    k = 0
    totals = {}
    for rec in tcats:
        if k >= len(tx):
            break
        acct = rec[:11]
        if acct not in accts_in:
            break
        rate = disc.get(accts_in[acct][112:122] + rec[11:17], disc.get("DEFAULT   " + rec[11:17]))
        if rate is None:
            break
        if rate == 0:
            continue
        exact = unzone(rec[17:28]) * rate / 1200
        written = unzone(tx[k][132:143])
        k += 1
        totals[acct] = wrap(totals.get(acct, Decimal(0)) + written, 9)
        if written != wrap(exact, 9):
            fail(f"tx {k}: {rec[17:28]} x {rate} / 1200 = {exact} written {written}")
            continue
        if exact != exact.quantize(CENT, ROUND_DOWN):
            tally["subcent"] += 1
            if exact.quantize(CENT, ROUND_HALF_UP) != exact.quantize(CENT, ROUND_DOWN):
                tally["rounding_would_differ"] += 1
            if exact < 0:
                tally["negative_toward_zero"] += 1
        if written == 0:
            tally["zero_amount"] += 1
        if abs(exact) >= 10 ** 9:
            tally["interest_overflow"] += 1

    if rc != "0" or not tcats or not accts_out:
        return
    # last account never rewritten; every other account rewritten as BAL + total, cycle fields 0
    last = tcats[-1][:11]
    tally["last_acct_runs"] += 1
    if accts_out.get(last) != accts_in[last]:
        fail(f"last account {last} was rewritten")
    else:
        tally["last_acct_untouched"] += 1
        if totals.get(last, 0) != 0 or unzone(accts_in[last][78:90]) or unzone(accts_in[last][90:102]):
            tally["last_acct_would_change"] += 1
    for acct in {r[:11] for r in tcats} - {last}:
        before, after = accts_in[acct], accts_out[acct]
        raw_bal = unzone(before[12:24]) + totals.get(acct, Decimal(0))
        if unzone(after[12:24]) != wrap(raw_bal, 10) or unzone(after[78:90]) or unzone(after[90:102]):
            fail(f"account {acct} not rewritten as BAL + interest with cycle fields zeroed")
        tally["rewritten"] += 1
        if abs(raw_bal) >= 10 ** 10:
            tally["balance_overflow"] += 1


def main():
    args = [a for a in sys.argv[1:] if a != "--md"]
    md = "--md" in sys.argv
    roots = [Path(a) for a in args]
    keys = [("records", "TRANSACT records checked"),
            ("cat0005", "records with type 01 / category 0005"),
            ("subcent", "amounts with a sub-cent remainder, all truncated toward zero"),
            ("rounding_would_differ", "of those, amounts half-up rounding would change"),
            ("negative_toward_zero", "negative amounts truncated toward zero"),
            ("zero_amount", "0.00 interest records written"),
            ("interest_overflow", "interest amounts that overflowed S9(9)V99 (high digits dropped)"),
            ("balance_overflow", "ACCT-CURR-BAL updates that overflowed S9(10)V99"),
            ("rewritten", "accounts rewritten as BAL + interest, cycle credit/debit 0"),
            ("last_acct_runs", "completed runs with category balances"),
            ("last_acct_untouched", "of those, last account left byte-identical to input"),
            ("last_acct_would_change", "of those, last account that a correct program would change")]
    tallies, fails = [], []
    for root in roots:
        tally = dict.fromkeys(k for k, _ in keys)
        tally = {k: 0 for k in tally}
        for scn in sorted(p for p in (HERE / "scenarios").iterdir() if p.is_dir()):
            check(scn, root / scn.name, tally, fails)
        tallies.append(tally)
    names = [r.name if r.name not in ("", ".") else str(r) for r in roots]
    if md:
        print("| Check | " + " | ".join(names) + " |")
        print("|---|" + "---:|" * len(roots))
        for k, label in keys:
            print(f"| {label} | " + " | ".join(str(t[k]) for t in tallies) + " |")
    else:
        for k, label in keys:
            print(f"{label:70} " + " ".join(f"{t[k]:>6}" for t in tallies))
    for f in fails[:20]:
        print("FAIL", f, file=sys.stderr)
    print(("FAIL: %d violations" % len(fails)) if fails else "ALL QUIRKS PRESENT", file=sys.stderr)
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
