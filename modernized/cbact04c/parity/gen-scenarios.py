#!/usr/bin/env python3
"""Generates the synthetic parity scenarios under scenarios/ (deterministic, seeded).

The app/data/ASCII sample only exercises small positive/negative balances and the DEFAULT
disclosure group, so these scenarios add the edge cases: truncation of positive and negative
interest (penny-cutoff), negative and zero rates, group fallback, field overflow, the last-account defect,
empty input and every file-error abend path.
"""
import random
import shutil
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "scenarios"
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"


def zoned(value, int_digits, scale=2):
    units = int((Decimal(value) * (10 ** scale)).to_integral_value())
    digits = str(abs(units)).rjust(int_digits + scale, "0")[-(int_digits + scale):]
    return digits[:-1] + (NEG if units < 0 else POS)[int(digits[-1])]


def tcat(acct, typ, cat, bal):
    return f"{acct:011d}{typ}{cat:04d}{zoned(bal, 9)}" + "0" * 22


def disc(group, typ, cat, rate, raw_rate=None):
    return f"{group:<10}{typ}{cat:04d}{raw_rate or zoned(rate, 4)}" + "0" * 28


def acct(acct_id, bal, group, cyc_credit="12.34", cyc_debit="-56.78", raw_bal=None):
    rec = (f"{acct_id:011d}Y{raw_bal or zoned(bal, 10)}{zoned('5000', 10)}{zoned('1000', 10)}"
           f"2014-11-202025-05-202025-05-20{zoned(cyc_credit, 10)}{zoned(cyc_debit, 10)}"
           f"{'12345':<10}{group:<10}")
    return rec + " " * (300 - len(rec))


def xref(card, cust, acct_id):
    return f"{card:016d}{cust:09d}{acct_id:011d}" + " " * 14


def write(name, tcats, discs, accts, xrefs, parm=None, timestamp=None, missing=None):
    d = ROOT / name
    if d.exists():
        shutil.rmtree(d / "input", ignore_errors=True)
    (d / "input").mkdir(parents=True, exist_ok=True)
    for fname, rows in (("tcatbal.txt", tcats), ("discgrp.txt", discs),
                        ("acctdata.txt", accts), ("cardxref.txt", xrefs)):
        (d / "input" / fname).write_text("".join(r + "\n" for r in rows))
    for fname, value in (("parm", parm), ("timestamp", timestamp), ("missing", missing)):
        p = d / fname
        if value is None:
            p.unlink(missing_ok=True)
        else:
            p.write_text(value + "\n")


def base_discs():
    rows = []
    for g, r in (("GOLD", "15.25"), ("DEFAULT", "24.99"), ("NEGRATE", "-3.33")):
        for typ, cat in (("01", 1), ("01", 2), ("02", 1), ("03", 1)):
            rows.append(disc(g, typ, cat, r))
    rows.append(disc("ZERO", "01", 1, "0"))
    rows.append(disc("PLAIN", "01", 1, None, raw_rate="001899"))  # unsigned rate 18.99
    return rows


def edge():
    accts = [
        acct(1, "1000.00", "GOLD"),
        acct(2, "-250.50", "MISSING"),          # group not in DISCGRP -> DEFAULT
        acct(3, "0", "NEGRATE"),
        acct(4, "100.00", "ZERO"),               # rate 0 -> no transaction
        acct(5, None, "PLAIN", raw_bal="000000019400"),  # unsigned balance, no sign overpunch
        acct(6, "9999999999.99", "GOLD"),        # ACCT-CURR-BAL overflows
        acct(7, "42.00", "GOLD"),                # last account: never rewritten (COBOL defect)
    ]
    xrefs = [xref(4000000000000000 + i, 100 + i, i) for i in range(1, 8)]
    tcats = [
        tcat(1, "01", 1, "1234.56"),    # 19.536912 -> 15.68...; truncation
        tcat(1, "01", 2, "-333.33"),    # negative balance truncates toward zero
        tcat(1, "02", 1, "0.07"),       # interest below one cent -> 0.00 transaction
        tcat(1, "03", 1, "999999999.99"),
        tcat(2, "01", 1, "-1.01"),
        tcat(2, "02", 1, "777.77"),
        tcat(3, "01", 1, "500.00"),
        tcat(3, "01", 2, "-500.00"),
        tcat(4, "01", 1, "100.00"),
        tcat(5, "01", 1, "1234.56"),
        tcat(6, "01", 1, "999999999.99"),
        tcat(6, "01", 2, "999999999.99"),
        tcat(7, "01", 1, "55.55"),
    ]
    discs = base_discs() + [disc("GOLD", "01", 2, "9999.99"), disc("GOLD", "03", 1, "9999.99")]
    discs = [d for d in discs if not (d[:16] in ("GOLD      010002", "GOLD      030001")
                                      and d[16:22] == zoned("15.25", 4))]
    write("edge-cases", tcats, discs, accts, xrefs, parm="20221231", timestamp="2022-12-31T23:59:59.99")


def fuzz(seed, n_accounts):
    rnd = random.Random(seed)
    groups = ["GOLD", "DEFAULT", "NEGRATE", "ZERO", "PLAIN", "NOSUCH"]
    accts, xrefs, tcats = [], [], []
    for i in range(n_accounts):
        aid = 10_000_000 + i * 7
        bal = Decimal(rnd.randint(-10**11, 10**12)) / 100
        accts.append(acct(aid, bal, rnd.choice(groups),
                          Decimal(rnd.randint(0, 10**7)) / 100, Decimal(rnd.randint(-10**7, 0)) / 100))
        xrefs.append(xref(5_000_000_000_000_000 + i * 13, i, aid))
        for typ, cat in rnd.sample([("01", 1), ("01", 2), ("02", 1), ("03", 1)], rnd.randint(1, 4)):
            mag = rnd.choice([10**3, 10**6, 10**9, 10**11])
            tcats.append(tcat(aid, typ, cat, Decimal(rnd.randint(-mag, mag)) / 100))
    discs = base_discs()
    for typ, cat in (("01", 1), ("01", 2), ("02", 1), ("03", 1)):
        discs.append(disc("FUZZ", typ, cat, Decimal(rnd.randint(-99999, 999999)) / 100))
    accts.append(acct(99_999_999_999, "1.00", "FUZZ"))
    xrefs.append(xref(9_999_999_999_999_999, 999, 99_999_999_999))
    tcats.append(tcat(99_999_999_999, "01", 1, "123.45"))
    write(f"fuzz-seed{seed}", tcats, discs, accts, xrefs)


def penny():
    """Cent cut-off probes: every case has a sub-cent remainder where truncation and rounding differ."""
    keys = [(t, c) for t in ("01", "02", "03") for c in (1, 2)]
    discs = base_discs() + [disc(g, t, c, r) for g, r in (("PENNY", "12.00"), ("THIRD", "10.00")) for t, c in keys]
    plan = [
        # PENNY: interest = balance / 100 (4 decimals); THIRD: balance / 120 (repeating decimals)
        (1, "PENNY", ["100.99", "100.99", "100.99"]),            # 1.0099 x3: each 1.00, total 3.00 (not 3.02/3.03)
        (2, "PENNY", ["100.50", "-100.50", "0.99", "-0.99", "199.99", "-199.99"]),
        (3, "THIRD", ["200.00", "-200.00", "1.00", "999999999.99", "-999999999.99", "0.11"]),
        (4, "PENNY", ["0.01", "-0.01"]),
        (5, "GOLD", ["1.00"]),                                     # last account (never rewritten)
    ]
    accts, xrefs, tcats = [], [], []
    for aid, group, bals in plan:
        accts.append(acct(aid, "1000.00", group))
        xrefs.append(xref(4300000000000000 + aid, 300 + aid, aid))
        tcats += [tcat(aid, t, c, b) for (t, c), b in zip(keys, bals)]
    write("penny-cutoff", tcats, discs, accts, xrefs)


def errors():
    accts = [acct(1, "100.00", "GOLD"), acct(2, "200.00", "GOLD")]
    xrefs = [xref(4111111111111111, 1, 1), xref(4222222222222222, 2, 2)]
    tc = [tcat(1, "01", 1, "1000.00"), tcat(2, "01", 1, "2000.00")]
    d = base_discs()
    write("err-account-not-found", tc + [tcat(3, "01", 1, "10.00")], d, accts, xrefs)
    write("err-xref-not-found", tc + [tcat(3, "01", 1, "10.00")], d, accts + [acct(3, "1", "GOLD")], xrefs)
    write("err-no-default-group", tc, [r for r in d if not r.startswith("DEFAULT")],
          [accts[0], acct(2, "200.00", "UNKNOWN")], xrefs)
    for dd in ("TCATBALF", "XREFFILE", "DISCGRP", "ACCTFILE"):
        write(f"err-missing-{dd.lower()}", tc, d, accts, xrefs, missing=dd)
    write("empty-tcatbal", [], d, accts, xrefs)
    write("single-account", [tcat(2, "01", 1, "2000.00"), tcat(2, "01", 2, "-2000.00")], d, accts, xrefs)


if __name__ == "__main__":
    edge()
    penny()
    for seed, n in ((1, 200), (2, 200), (3, 500)):
        fuzz(seed, n)
    errors()
    print("\n".join(sorted(p.name for p in ROOT.iterdir())))
