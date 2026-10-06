#!/usr/bin/env python3
"""Record-by-record comparison of COBOL and Java outputs for every scenario.

Usage: compare.py [--mask-timestamps] <cobol-out-root> <java-out-root> [report.md]
Each root holds one directory per scenario with sysout.txt, rc, transact.dat, acctdata.txt.
--mask-timestamps: for runs on the real clock, TRAN-ORIG-TS/TRAN-PROC-TS (bytes 279-330 of each
TRANSACT record) are checked to be well-formed DB2 timestamps with ORIG == PROC, then masked.
Exits 1 if any record differs.
"""
import re
import sys
from pathlib import Path

# artifact -> (description, splitter)
def fixed(n):
    return lambda b: [b[i:i + n] for i in range(0, len(b), n)]

TS = slice(278, 330)   # TRAN-ORIG-TS X(26) + TRAN-PROC-TS X(26) in CVTRA05Y
DB2_TS = re.compile(rb"\d{4}-\d{2}-\d{2}-\d{2}\.\d{2}\.\d{2}\.\d{2}0000")


def mask_timestamps(records):
    out = []
    for r in records:
        orig, proc = r[278:304], r[304:330]
        if DB2_TS.fullmatch(orig) and orig == proc:
            r = r[:278] + b"#" * 52 + r[330:]
        out.append(r)
    return out


def lines(b):
    return b.split(b"\n")[:-1] if b.endswith(b"\n") else b.split(b"\n") if b else []

ARTIFACTS = [
    ("transact.dat", "TRANSACT records (350 bytes)", fixed(350)),
    ("acctdata.txt", "ACCTFILE records after run (300 bytes)", lines),
    ("sysout.txt", "SYSOUT lines", lines),
    ("rc", "return code", lines),
]


def compare(cobol: Path, java: Path, mask=False):
    rows, diffs = [], []
    for name, _, split in ARTIFACTS:
        a = split((cobol / name).read_bytes())
        b = split((java / name).read_bytes())
        if mask and name == "transact.dat":
            a, b = mask_timestamps(a), mask_timestamps(b)
        same = sum(1 for x, y in zip(a, b) if x == y)
        mismatched = max(len(a), len(b)) - same
        rows.append((name, len(a), len(b), same, mismatched))
        for i in range(max(len(a), len(b))):
            x = a[i] if i < len(a) else None
            y = b[i] if i < len(b) else None
            if x != y and len(diffs) < 5:
                diffs.append(f"  {name} record {i + 1}:\n    cobol={x!r}\n    java ={y!r}")
    return rows, diffs


def main():
    args = sys.argv[1:]
    mask = "--mask-timestamps" in args
    args = [a for a in args if a != "--mask-timestamps"]
    cobol_root, java_root = Path(args[0]), Path(args[1])
    report = Path(args[2]) if len(args) > 2 else None
    out = ["| Scenario | Artifact | COBOL records | Java records | Identical | Different |",
           "|---|---|---:|---:|---:|---:|"]
    failed = False
    for scn in sorted(p for p in cobol_root.iterdir() if p.is_dir()):
        rows, diffs = compare(scn, java_root / scn.name, mask)
        for name, na, nb, same, bad in rows:
            out.append(f"| {scn.name} | {name} | {na} | {nb} | {same} | {bad} |")
            failed |= bad > 0
        status = "MATCH" if not any(r[4] for r in rows) else "DIFF"
        print(f"{status:5} {scn.name}")
        for d in diffs:
            print(d)
    table = "\n".join(out)
    print(table)
    if report:
        report.write_text(table + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
