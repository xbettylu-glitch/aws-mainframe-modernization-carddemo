# interest-calculator

Java 17 port of the CardDemo batch program `CBACT04C` (JCL `INTCALC`, monthly interest).
Business rules are described in [docs/CBACT04C-spec.md](../../../docs/CBACT04C-spec.md).

The port is behaviour-for-behaviour: for the same inputs it writes byte-identical TRANSACT
records, account master updates and SYSOUT, and ends with the same abend code. That includes
the COBOL quirks:

- Interest is **truncated** to cents (toward zero), never rounded: `1234.56 x 18.99% / 12 = 19.5369 -> 19.53`, `-5.5555 -> -5.55`.
- Values too large for their COBOL field lose their leading digits instead of failing.
- The **last account** in the balance file gets its interest transactions but its balance is
  never updated (the COBOL `PERFORM UNTIL` loop exits before the final `1050-UPDATE-ACCOUNT`).
- `1400-COMPUTE-FEES` is empty.

## Build and run

```bash
mvn package
java -jar target/interest-calculator-1.0.0.jar --parm 2022071800 \
  --tcatbal tcatbal.txt --xref cardxref.txt --acct acctdata.txt --discgrp discgrp.txt \
  --transact transact.dat [--timestamp 2022-07-18T01:02:03.45]
```

Input files use the `app/data/ASCII` layout: one fixed-length record per line, signed numbers
with EBCDIC-style overpunched signs. `--acct` is updated in place (VSAM KSDS `REWRITE`).
`TRANSACT` is written as back-to-back 350-byte records (RECFM=F). The exit status is 0 on success
and 999 on an abend (the shell reports it as 231). `--timestamp` freezes the clock used for
`TRAN-ORIG-TS`/`TRAN-PROC-TS`; without it the current time is used, as in the COBOL.

## COBOL paragraph to Java method

All in `InterestCalculator` unless noted.

| COBOL paragraph | Java method |
|---|---|
| PROCEDURE DIVISION mainline | `run()` / `mainline()` |
| main `PERFORM UNTIL END-OF-FILE = 'Y'` loop | `processCategoryBalances()` |
| `0000-TCATBALF-OPEN` | `openTcatbalFile()` |
| `0100-XREFFILE-OPEN` | `openXrefFile()` |
| `0200-DISCGRP-OPEN` | `openDiscgrpFile()` |
| `0300-ACCTFILE-OPEN` | `openAccountFile()` |
| `0400-TRANFILE-OPEN` | `openTransactFile()` |
| `1000-TCATBALF-GET-NEXT` | `getNextCategoryBalance()` |
| `1050-UPDATE-ACCOUNT` | `updateAccount()` |
| `1100-GET-ACCT-DATA` | `getAccountData()` |
| `1110-GET-XREF-DATA` | `getXrefData()` |
| `1200-GET-INTEREST-RATE` | `getInterestRate()` |
| `1200-A-GET-DEFAULT-INT-RATE` | `getDefaultInterestRate()` |
| `1300-COMPUTE-INTEREST` | `computeInterest()` (formula in `monthlyInterest()`) |
| `1300-B-WRITE-TX` | `writeInterestTransaction()` |
| `1400-COMPUTE-FEES` | `computeFees()` (empty, as in the COBOL) |
| `9000-TCATBALF-CLOSE` | `closeTcatbalFile()` |
| `9100-XREFFILE-CLOSE` | `closeXrefFile()` |
| `9200-DISCGRP-CLOSE` | `closeDiscgrpFile()` |
| `9300-ACCTFILE-CLOSE` | `closeAccountFile()` |
| `9400-TRANFILE-CLOSE` | `closeTransactFile()` |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | `db2FormatTimestamp()` |
| `9999-ABEND-PROGRAM` | `abendWithStatus()` throws `AbendException`; `run()` returns 999 |
| `9910-DISPLAY-IO-STATUS` | `formatIoStatus()` |

Copybooks map to record classes: `CVTRA01Y` -> `TranCatBalRecord`, `CVACT03Y` -> `CardXrefRecord`,
`CVTRA02Y` -> `DisclosureGroupRecord`, `CVACT01Y` -> `AccountRecord`, `CVTRA05Y` -> `TransactionRecord`.
Signed `PIC S9(n)V99` fields and COBOL store semantics live in `ZonedDecimal`; VSAM files are
emulated by `IndexedFile` (with COBOL file status codes) and `SequentialOutputFile`.

## Parity harness (`parity/`)

`parity/run-parity.sh` compiles the **unmodified** `app/cbl/CBACT04C.cbl` with GnuCOBOL
(`-fsign=EBCDIC`), runs it and the Java port on every scenario in `parity/scenarios/`, and
compares TRANSACT, the updated account file, SYSOUT and the return code record by record
(`parity/RESULTS.md`). The COBOL outputs are saved as `scenarios/<name>/expected/`, which
`GoldenParityTest` replays on every `mvn test`, so no COBOL compiler is needed for the unit tests.

| Scenario | What it covers |
|---|---|
| `sample-raw` | `app/data/ASCII` as shipped (category balances are all zero) |
| `sample-posted` | `app/data/ASCII` after the POSTTRAN step (original `CBTRN02C` run on `dailytran.txt`), i.e. what INTCALC sees in the normal job order |
| `edge-cases` | truncation of positive/negative interest, sub-cent interest, negative and zero rates, DEFAULT fallback, unsigned fields, field overflow, short PARM, last-account defect |
| `fuzz-seed1..3` | 903 random accounts / 2,287 category balances over the full field ranges, all disclosure groups |
| `single-account`, `empty-tcatbal` | boundary cases |
| `err-*` | every abend path: account/xref/default-rate not found, each input dataset missing |

Harness-only helpers in `parity/cobol/`: `PARLOAD` (loads the ASCII files into indexed files,
like the IDCAMS REPRO steps), `PARUNLD` (unloads them after the run), `PARDRVR` (passes the JCL
`PARM`) and `cee3abd.c` (stand-in for the LE `CEE3ABD` abend service). Requires GnuCOBOL 3.x
with indexed-file support, Java 17, Maven and Python 3.
