# CBACT04C modernization pilot (INTCALC monthly interest)

Pilot rewrite of the CardDemo batch program [`app/cbl/CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl)
(JCL [`app/jcl/INTCALC.jcl`](../../app/jcl/INTCALC.jcl), `EXEC PGM=CBACT04C,PARM='2022071800'`) in
Java 17, with a harness that shows it behaves the same as the COBOL.

**Result:** on all 16 scenarios (2,324 TRANSACT records, 1,032 ACCTFILE records, every abend path)
the Java output is byte-identical to the unmodified COBOL compiled with GnuCOBOL. That covers
TRANSACT, the rewritten ACCTFILE, SYSOUT and the return code. An independent third
implementation written from the rules below (`parity/oracle.py`) also matches both, byte for byte.
Details are in [Equivalence results](#equivalence-results).

```
modernized/cbact04c/
  pom.xml, src/main/java/...   Java port (com.carddemo.interest)
  src/test/java/...            unit tests + GoldenParityTest (replays the saved COBOL baselines)
  run-sample.sh                runs the port on the app/data/ASCII extract
  parity/                      verification harness (COBOL vs Java vs oracle)
```

## Behaviour reproduced

All of these match the COBOL exactly:

- **Inputs** use the copybook layouts: TCATBALF `TRAN-CAT-BAL-RECORD` (CVTRA01Y, 50 bytes),
  ACCTFILE `ACCOUNT-RECORD` (CVACT01Y, 300), XREFFILE `CARD-XREF-RECORD` (CVACT03Y, 50), read through
  the alternate key `FD-XREF-ACCT-ID`, DISCGRP `DIS-GROUP-RECORD` (CVTRA02Y, 50). Each file is read
  as the ASCII unload format in `app/data/ASCII` (one fixed-length record per line, zoned decimals
  with EBCDIC-style overpunched signs).
- **Control break:** TCATBALF is read in key order. When `TRANCAT-ACCT-ID` changes, the previous
  account is rewritten, `WS-TOTAL-INT` is reset, and the new account and its xref are read.
- **Rate lookup:** the key is `ACCT-GROUP-ID` + type + category. If that key is missing (status 23),
  the program retries with group `DEFAULT` and the same type + category. If `DEFAULT` is also
  missing, it abends.
- **Interest** is calculated only when `DIS-INT-RATE` is not zero:
  `WS-MONTHLY-INT = TRAN-CAT-BAL * DIS-INT-RATE / 1200`, stored in `PIC S9(09)V99` with no ROUNDED.
  The result is truncated toward zero to the cent, and digits above 9 integer places are dropped.
  Each amount is added to `WS-TOTAL-INT`.
- **TRANSACT** (CVTRA05Y, 350 bytes, RECFM=F) gets one record per qualifying balance:
  `TRAN-ID` = PARM-DATE + 6-digit sequence, type `01`, category `0005`, source `System`,
  description `Int. for a/c <ACCT-ID>`, amount, merchant id 0 with blank merchant name/city/zip,
  `TRAN-CARD-NUM` = `XREF-CARD-NUM`, and `TRAN-ORIG-TS` = `TRAN-PROC-TS` = CURRENT-DATE in DB2 format.
- **ACCTFILE REWRITE** sets `ACCT-CURR-BAL += WS-TOTAL-INT` and sets `ACCT-CURR-CYC-CREDIT` and
  `ACCT-CURR-CYC-DEBIT` to 0.
- **Fees:** `1400-COMPUTE-FEES` is an empty stub, and it stays empty in the port.
- **Errors:** the same SYSOUT messages and file-status lines are printed, and the program abends
  with code 999 through `CEE3ABD` (the GnuCOBOL/Java process exit status is 231).

## Running it

Needs Java 17 and Maven. The harness also needs GnuCOBOL 3.x with indexed-file support and Python 3.

```bash
cd modernized/cbact04c
mvn package                                   # builds target/interest-calculator-1.0.0.jar, runs unit tests
./run-sample.sh                               # runs on app/data/ASCII, output in ./sample-out (real clock)
./run-sample.sh out 2022071800 2022-07-18T01:02:03.45   # same, frozen clock

java -jar target/interest-calculator-1.0.0.jar --parm 2022071800 \
  --tcatbal tcatbal.txt --xref cardxref.txt --acct acctdata.txt --discgrp discgrp.txt \
  --transact transact.dat [--timestamp 2022-07-18T01:02:03.45]
```

`--acct` is updated in place, like a VSAM REWRITE. `run-sample.sh` works on a copy, so `app/data`
is never modified. `--timestamp` injects the clock behind `TRAN-ORIG-TS` and `TRAN-PROC-TS`.
Without it the port uses the current time, as the COBOL does.

**Sample data:** `app/data/ASCII/{tcatbal,cardxref,acctdata,discgrp}.txt` are the unloads of the
four input VSAM files. As shipped, all of their category balances are zero. The `sample-posted`
scenario is the realistic one: it is the same extract after POSTTRAN, built by running the original
`CBTRN02C` on `dailytran.txt` with GnuCOBOL.

## Verification harness (`parity/`)

```bash
parity/validate.sh          # everything below + run-sample.sh + mvn test; writes parity/RESULTS.md
parity/run-parity.sh        # compile COBOL, run COBOL + Java on every scenario, diff, run oracle
parity/run-parity-live.sh   # same on the REAL clock, timestamp fields masked
parity/oracle.py            # independent spec model vs saved COBOL baselines (no compiler needed)
parity/check-quirks.py      # asserts the quirks below hold in the COBOL baselines and Java outputs
parity/mutation-check.sh    # proves the diff catches rounding instead of truncation
```

The latest full run and the pilot recommendation are in [`PILOT-SUMMARY.md`](PILOT-SUMMARY.md).

1. **Legacy run.** `run-parity.sh` compiles the unmodified `CBACT04C.cbl` with GnuCOBOL
   (`cobc -fsign=EBCDIC`). Helpers in `parity/cobol/` load each scenario into indexed files, as the
   IDCAMS REPRO steps would (`PARLOAD`), pass the JCL PARM (`PARDRVR`), stand in for LE `CEE3ABD`,
   and unload ACCTFILE after the run (`PARUNLD`). The COBOL outputs are saved as
   `scenarios/<name>/expected/`.
2. **Modernized run.** The Java port runs on the same inputs, and `compare.py` diffs TRANSACT
   (350-byte records), ACCTFILE, SYSOUT and the return code record by record. `validate.sh`
   collects the results of every check into [`parity/RESULTS.md`](parity/RESULTS.md).
3. **Independent oracle.** `oracle.py` is a ~150-line Python `Decimal` model written only from the
   rules on this page, with no shared code. It rebuilds all four artifacts and diffs them against the
   COBOL baseline and against the Java output. This is the "expected output computed by hand" check:
   it confirms the baselines follow the documented rules, not only that two implementations agree.
4. **Timestamps.** CURRENT-DATE is handled in two ways:
   - **Controlled clock** (default): GnuCOBOL's `COB_CURRENT_DATE` freezes `FUNCTION CURRENT-DATE`
     for the COBOL, and `--timestamp` injects the same instant into Java. TRANSACT can then be
     compared in full, timestamps included. A per-scenario `timestamp` file overrides the default.
   - **Masked** (`run-parity-live.sh`, `LIVE_CLOCK=1`): both programs run on the real clock.
     `compare.py --mask-timestamps` checks that bytes 279-330 of every record hold two identical,
     well-formed `YYYY-MM-DD-HH.MM.SS.hh0000` values, then masks them before diffing.
5. **Regression.** `GoldenParityTest` replays every saved baseline on each `mvn test`, so CI does
   not need a COBOL compiler.
6. **Sensitivity.** `mutation-check.sh` rebuilds scratch copies of the port with
   `RoundingMode.DOWN` replaced. Every mutant must fail the comparison.

| Scenario | What it covers |
|---|---|
| `sample-raw` | `app/data/ASCII` as shipped (category balances are all zero) |
| `sample-posted` | `app/data/ASCII` after the POSTTRAN step (original `CBTRN02C` run on `dailytran.txt`), i.e. what INTCALC sees in the normal job order |
| `edge-cases` | truncation of positive/negative interest, sub-cent interest, negative and zero rates, DEFAULT fallback, unsigned fields, field overflow, short PARM, last-account defect |
| `penny-cutoff` | cent cut-off probes: exactly half a cent (`1.005 -> 1.00`), `x.xx99` amounts, repeating decimals (`1.6666 -> 1.66`), negative sub-cent amounts (`-0.0099 -> +0.00`), and an account whose balance adds the truncated amounts (`3 x 1.0099 -> +3.00`) |
| `fuzz-seed1..3` | 903 random accounts / 2,287 category balances over the full field ranges, all disclosure groups |
| `single-account`, `empty-tcatbal` | boundary cases |
| `err-*` | every abend path: account/xref/default-rate not found, each input dataset missing |

## Equivalence results

Run on GnuCOBOL 3.1.2 and OpenJDK 17.

| Check | Scenarios | Result |
|---|---:|---|
| COBOL vs Java, frozen clock (`run-parity.sh`) | 16 / 16 | identical: 2,324 TRANSACT records, 1,032 ACCTFILE records, 4,760 SYSOUT lines, all return codes |
| COBOL vs Java, real clock, timestamps masked (`run-parity-live.sh`) | 16 / 16 | identical after masking. Without masking, 11 scenarios differ, which confirms the clock really was live |
| Oracle vs COBOL baseline | 16 / 16 | byte-identical |
| Oracle vs Java | 16 / 16 | byte-identical |
| `run-sample.sh` (frozen clock) vs COBOL on the shipped `app/data/ASCII` | 1 / 1 | identical: 50 TRANSACT records + ACCTFILE |
| `mvn test` (`GoldenParityTest` + unit tests) | | pass |
| Mutation: `HALF_UP` / `HALF_EVEN` / `UP` / `CEILING` / `FLOOR` instead of truncation | | all detected: 10 / 10 / 10 / 10 / 6 of 16 scenarios differ |

Per-artifact counts for each scenario are in [`parity/RESULTS.md`](parity/RESULTS.md).

### Hand-worked examples (checked against the COBOL output)

| Scenario / record | Balance | Rate | Exact | Written |
|---|---:|---:|---:|---:|
| sample-posted, acct 1 cat 01/0001 | 1,164.87 | 15.00 (DEFAULT) | 14.560875 | **14.56** |
| sample-posted, acct 2 cat 01/0001 | 2,339.97 | 15.00 (DEFAULT) | 29.249625 | **29.24** (rounding would give 29.25) |
| edge-cases, acct 1 cat 01/0001 | 1,234.56 | 15.25 | 15.6891 | **15.68** |
| edge-cases, acct 1 cat 01/0002 | -333.33 | 9,999.99 | -2,777.7472 | **-2,777.74** (toward zero) |
| edge-cases, acct 1 cat 02/0001 | 0.07 | 15.25 | 0.00088 | **0.00**, but a record is still written |
| edge-cases, acct 1 cat 03/0001 | 999,999,999.99 | 9,999.99 | 8,333,324,999.91 | **333,324,999.91** (leading digit lost) |
| penny-cutoff, acct 1, 3 x | 100.99 | 12.00 | 3 x 1.0099 | 3 x **1.00**, so the balance goes up by 3.00, not 3.03 |

## Behavioural quirks discovered

Every item below was reproduced by running the unmodified COBOL with GnuCOBOL. The port keeps
each one on purpose.

1. **Truncation, not rounding.** No `ROUNDED`, so interest is cut toward zero at the cent. On the
   posted sample data, 28 of the 50 interest amounts would change by a cent under half-up rounding.
   Negative balances give negative interest (credits), truncated toward zero: `-0.0099 -> +0.00`.
2. **The last account is never updated.** After the final TCATBALF read sets `END-OF-FILE`, the
   `PERFORM UNTIL` loop exits. The `ELSE PERFORM 1050-UPDATE-ACCOUNT` branch, meant for EOF, is never
   reached. As a result, the last account in key order gets its interest transactions written, but
   its balance is not increased and its cycle credit/debit are not zeroed. In `sample-posted`, 49 of
   50 accounts are rewritten and account 50 is unchanged. This looks like a real defect.
3. **PARM-DATE *is* used, but only in `TRAN-ID`.** It plays no part in the interest math or the
   timestamps, which agrees with the brief. However, `TRAN-ID` = PARM-DATE (10 bytes) + a 6-digit
   counter (`2022071800000001`, ...). A PARM shorter than 10 characters leaves spaces in GnuCOBOL
   (`20221231  000001`). On z/OS the bytes past the PARM length are not guaranteed. The counter is
   `PIC 9(06)`, so it would wrap and repeat IDs after 999,999 transactions in one run.
4. **Zero-amount transactions.** The check is on the rate, not the amount. Zero balances and
   sub-cent results still produce `0.00` interest records. The shipped `app/data/ASCII` (all
   balances zero) yields 50 transactions of 0.00 and leaves every account unchanged.
5. **Category is `0005`, not `05`.** `MOVE '05' TO TRAN-CAT-CD` targets a `PIC 9(04)` field.
6. **Silent overflow.** There is no `ON SIZE ERROR`. Interest that needs more than 9 integer digits
   loses its leading digits (example above), and `ACCT-CURR-BAL` (`S9(10)V99`) wraps the same way
   (edge-cases acct 6).
7. **Missing-key messages are misleading:**
   - When the account is missing, the program prints `ACCOUNT NOT FOUND: <id>`, then
     `ERROR READING ACCOUNT FILE` and `FILE STATUS IS: NNNN0023`, then abends.
   - When the **xref** is missing, it prints the same `ACCOUNT NOT FOUND: <id>` text, then
     `ERROR READING XREF FILE`, then abends.
   - When the disclosure group is missing, it prints `DISCLOSURE GROUP RECORD MISSING` and
     `TRY WITH DEFAULT GROUP CODE` for every category record that falls back. In the posted sample
     no account has an `ACCT-GROUP-ID`, so all 100 records fall back to DEFAULT and SYSOUT carries
     200 such lines.
   - A missing DEFAULT prints `ERROR READING DEFAULT DISCLOSURE GROUP`, then abends.
8. **Copy-paste open-error texts.** If DISCGRP fails to open, the message is
   `ERROR OPENING DALY REJECTS FILE`. If XREFFILE fails to open, the status is glued onto the text:
   `ERROR OPENING CROSS REF FILE35`.
9. **An abend leaves partial output.** Transactions already written and accounts already rewritten
   are kept; there is no rollback. For example, `err-account-not-found` ends with 2 TRANSACT records
   and account 1 updated. Restarting the job would apply that interest twice.
10. **SYSOUT volume.** Every TCATBALF record is `DISPLAY`ed.
11. **Timestamps.** CURRENT-DATE is read separately for each transaction, so timestamps can differ
    within one run. ORIG and PROC are always equal. The format is `YYYY-MM-DD-HH.MM.SS.hh0000`:
    hundredths of a second plus four literal zeros.
12. **Key order and the xref alternate index.** TCATBALF is a KSDS read sequentially, so the control
    break depends on key order. The xref is looked up through the alternate key on account id (the
    JCL's `XREFFIL1` AIX path). If an account had several cards, the first card in the AIX would be
    used. The sample data has one card per account.
13. **Data format.** The ASCII extract keeps EBCDIC-style overpunched signs (`{`, `A-I` positive;
    `}`, `J-R` negative). The COBOL therefore has to be compiled with `-fsign=EBCDIC`, and the port
    decodes the same convention.

### Not covered by this pilot

- GnuCOBOL stands in for Enterprise COBOL on z/OS, so platform-specific behaviour is not exercised.
  This includes uninitialised WORKING-STORAGE (GnuCOBOL fills the TRANSACT `FILLER` with spaces),
  EBCDIC/binary VSAM files, and the U999 abend.
- Real VSAM datasets are replaced by ASCII unloads loaded into GnuCOBOL indexed files.

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
