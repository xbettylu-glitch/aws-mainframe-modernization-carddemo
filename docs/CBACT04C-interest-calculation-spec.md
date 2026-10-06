# CBACT04C – Monthly Interest Calculation: Business Specification

| Item | Value |
|---|---|
| Program | `app/cbl/CBACT04C.cbl` (batch COBOL) |
| Run by | `app/jcl/INTCALC.jcl`, step `STEP15` (`PARM='2022071800'`) |
| Copybooks | `CVTRA01Y` (category balance), `CVACT01Y` (account), `CVACT03Y` (card cross-reference), `CVTRA02Y` (disclosure group / interest rate), `CVTRA05Y` (transaction) |

## 1. What the program does

An account's balance is held as several **transaction category balances** (for example, purchases and cash
advances). Each category can carry its own annual interest rate, set by the account's **disclosure group**
(its pricing group). Once a month, for every category balance, the program:

1. finds the annual rate for the account's group and that category,
2. works out one month of interest on the category balance,
3. writes an interest transaction for that amount, and
4. when all of an account's categories are done, adds the account's total interest to its current balance and
   resets its cycle-to-date credit and debit totals to zero.

Fee calculation is planned but not built: paragraph `1400-COMPUTE-FEES` is empty (`* To be implemented`).

## 2. Inputs

### 2.1 Run parameter

| Field | Format | Use |
|---|---|---|
| `PARM-DATE` | 10 characters, e.g. `2022071800` | Used only as the first 10 characters of each generated transaction ID. It is not validated and plays no part in the calculation. |

### 2.2 Files

| DD name | Contents | How it is used | Layout | Fields that matter |
|---|---|---|---|---|
| `TCATBALF` | Transaction category balances (VSAM KSDS) | Read-only, sequentially in key order | `TRAN-CAT-BAL-RECORD` (`CVTRA01Y`), 50 bytes | Key: `TRANCAT-ACCT-ID` 9(11) + `TRANCAT-TYPE-CD` X(2) + `TRANCAT-CD` 9(4). Amount: `TRAN-CAT-BAL` S9(9)V99 |
| `ACCTFILE` | Account master (VSAM KSDS) | Read and rewritten, by `ACCT-ID` | `ACCOUNT-RECORD` (`CVACT01Y`), 300 bytes | Read: `ACCT-ID`, `ACCT-GROUP-ID`. Updated: `ACCT-CURR-BAL` S9(10)V99, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT` |
| `XREFFILE` (alternate-index path `XREFFIL1`) | Card ↔ account cross-reference (VSAM KSDS) | Read-only, by the **alternate key** `XREF-ACCT-ID` | `CARD-XREF-RECORD` (`CVACT03Y`), 50 bytes | `XREF-CARD-NUM`, copied onto the interest transaction |
| `DISCGRP` | Disclosure groups, i.e. interest rates (VSAM KSDS) | Read-only, by key | `DIS-GROUP-RECORD` (`CVTRA02Y`), 50 bytes | Key: `DIS-ACCT-GROUP-ID` X(10) + `DIS-TRAN-TYPE-CD` X(2) + `DIS-TRAN-CAT-CD` 9(4). Rate: `DIS-INT-RATE` S9(4)V99 |

`DIS-INT-RATE` is an **annual percentage rate**: `18.99` means 18.99% per year.

## 3. Outputs

### 3.1 Interest transactions (`TRANSACT`)

A new sequential file of 350-byte `TRAN-RECORD`s (`CVTRA05Y`); in the JCL it is a new generation
`AWS.M2.CARDDEMO.SYSTRAN(+1)`. It is opened `OUTPUT`, so it contains only this run's interest transactions.
One record is written for each category balance whose rate is not zero:

| Field | Value |
|---|---|
| `TRAN-ID` | `PARM-DATE` + a 6-digit run counter starting at `000001`, e.g. `2022071800000001` |
| `TRAN-TYPE-CD` / `TRAN-CAT-CD` | `01` / `0005` (the interest type and category) |
| `TRAN-SOURCE` | `System` |
| `TRAN-DESC` | `Int. for a/c ` followed by the 11-digit account ID |
| `TRAN-AMT` | The monthly interest for this category (section 4) |
| `TRAN-MERCHANT-ID` | `0` |
| `TRAN-MERCHANT-NAME`, `-CITY`, `-ZIP` | Spaces |
| `TRAN-CARD-NUM` | The card number found in the cross-reference for the account |
| `TRAN-ORIG-TS`, `TRAN-PROC-TS` | Time of writing, as `YYYY-MM-DD-HH.MM.SS.hh0000` (hundredths of a second, then `0000`) |

### 3.2 Account updates (`ACCTFILE`)

After the last category of an account has been processed, the account record is rewritten with:

- `ACCT-CURR-BAL` = `ACCT-CURR-BAL` + the account's total interest for this run
- `ACCT-CURR-CYC-CREDIT` = 0
- `ACCT-CURR-CYC-DEBIT` = 0

This happens even when the total interest is zero (for example, every category rate is 0%). Accounts that have no
category balance records are not touched. The category balance file itself is not changed.
**Exception: the last account in the file is never rewritten – see section 7.1.**

### 3.3 Job log (SYSOUT)

Start and end messages (`START OF EXECUTION OF PROGRAM CBACT04C`, `END OF EXECUTION OF PROGRAM CBACT04C`), each
category balance record as read, and the warnings and errors in section 6.

## 4. Calculation

### 4.1 Processing order

```
open all five files
for each category balance record, in key order (account, type code, category code):
    if this record starts a new account:
        rewrite the previous account, if there was one (3.2)
        set the account's running total interest to 0
        read the account record (gives ACCT-GROUP-ID)
        read the card cross-reference by account ID (gives the card number)
    look up the annual rate (section 5)
    if the rate is not 0:
        monthly interest = formula in 4.2
        add it to the account's running total
        write an interest transaction (3.1)
close all files
```

The program relies on the balance file being in key order, so that all of an account's categories arrive together.

### 4.2 Interest formula

```
monthly interest = category balance × annual rate ÷ 1200
```

Code (`1300-COMPUTE-INTEREST`):

```cobol
COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

Dividing by 1200 turns an annual percentage into a monthly fraction (÷ 12 months, ÷ 100 for percent). This is
simple interest on the balance as it stands when the job runs. There is no average daily balance, no day count,
no compounding, no grace period, and no minimum or maximum charge.

| Situation | Result |
|---|---|
| Positive balance | Positive interest (a charge) |
| Negative balance (credit) | Negative interest, which reduces the account balance |
| Zero balance, non-zero rate | A transaction for `0.00` is still written |
| Rate exactly `0` | Category skipped; no transaction |

### 4.3 Rounding

The interest is stored in `WS-MONTHLY-INT`, `PIC S9(09)V99` (two decimal places). The `COMPUTE` has no `ROUNDED`
clause, so digits beyond the second decimal are **dropped (truncated toward zero)**, never rounded:

| Category balance | Annual rate | Exact value | Stored interest |
|---|---|---|---|
| 1,234.56 | 18.99% | 19.536912 | **19.53** (rounding would give 19.54) |
| -333.33 | 20.00% | -5.5555 | **-5.55** |

(Both results checked by running the same `COMPUTE` and field definitions under GnuCOBOL 3.1.2.)

Each category is truncated separately and the truncated amounts are then added, so an account's total can be up to
one cent per category lower than truncating the account total once.

### 4.4 Field size limits

No arithmetic statement has `ON SIZE ERROR`, so a result too large for its field loses its leading digits with no
warning:

- Monthly interest and the account's running total hold at most ±999,999,999.99.
- `ACCT-CURR-BAL` holds at most ±9,999,999,999.99.
- The transaction ID counter is 6 digits; after 999,999 transactions in one run it wraps to `000000` and IDs repeat.

## 5. Interest rate lookup

1. Read `DISCGRP` with key (`ACCT-GROUP-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`).
2. Found (file status `00`): use its `DIS-INT-RATE`.
3. Not found (file status `23`): log `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`, then read
   again with group `DEFAULT` and the same type and category codes, and use that rate.
4. If the `DEFAULT` record is also missing, or either read fails in any other way, the job stops (section 6).

`DISCGRP` therefore needs a `DEFAULT` record for every type/category combination that some account's own group
does not define.

## 6. Error handling

Every open, read, write, rewrite, and close checks the file status. Only two non-`00` statuses are handled as
normal business conditions:

| Condition | Status | Handling |
|---|---|---|
| End of the category balance file | `10` | Normal end of the run |
| Account's group has no rate for this category | `23` on `DISCGRP` | Fall back to the `DEFAULT` group (section 5) |

Any other non-`00` status – including an account or cross-reference record that is not found – stops the job:

1. A message naming the failure is displayed (table below).
2. `9910-DISPLAY-IO-STATUS` displays `FILE STATUS IS: NNNN` followed by a 4-character status: `00xx` for ordinary
   statuses; for non-numeric or `9x` statuses, the first status character followed by the second byte's binary
   value as 3 digits.
3. `9999-ABEND-PROGRAM` displays `ABENDING PROGRAM` and calls `CEE3ABD` with abend code **999**, ending the step
   abnormally.

| Failing operation | Message |
|---|---|
| Open category balance file | `ERROR OPENING TRANSACTION CATEGORY BALANCE` |
| Open cross-reference file | `ERROR OPENING CROSS REF FILE` + status |
| Open disclosure group file | `ERROR OPENING DALY REJECTS FILE` (the text names the wrong file) |
| Open account file | `ERROR OPENING ACCOUNT MASTER FILE` |
| Open transaction output file | `ERROR OPENING TRANSACTION FILE` |
| Read category balance file | `ERROR READING TRANSACTION CATEGORY FILE` |
| Read account | `ACCOUNT NOT FOUND: <id>` (when missing), then `ERROR READING ACCOUNT FILE` |
| Read cross-reference | `ACCOUNT NOT FOUND: <id>` (when missing), then `ERROR READING XREF FILE` |
| Read disclosure group (other than `23`) | `ERROR READING DISCLOSURE GROUP FILE` |
| Read `DEFAULT` disclosure group | `ERROR READING DEFAULT DISCLOSURE GROUP` |
| Write interest transaction | `ERROR WRITING TRANSACTION RECORD` |
| Rewrite account | `ERROR RE-WRITING ACCOUNT FILE` |
| Close a file | `ERROR CLOSING <file name>` |

**Recovery:** there is no checkpoint, restart, or rollback. If the job stops partway, accounts already rewritten
keep their new balances, while the transaction file (`DISP=(NEW,CATLG,DELETE)`) is deleted. Restore the account
master before rerunning, or the accounts already processed will be charged interest twice.

## 7. Defects and observations

### 7.1 The last account's interest is never posted to its balance

The main loop is `PERFORM UNTIL END-OF-FILE = 'Y'`, which tests the condition *before* each pass. The read that
hits end of file sets `END-OF-FILE` to `'Y'` inside a pass; the loop then ends without another pass. The branch
`ELSE PERFORM 1050-UPDATE-ACCOUNT`, meant to save the final account, only runs when a pass *starts* with
`END-OF-FILE = 'Y'`, which never happens.

Effect: the last account in the balance file gets its interest transactions written, but its `ACCT-CURR-BAL` is not
increased and its cycle credit/debit totals are not reset.

### 7.2 Other observations

- **One card per account.** The cross-reference is read by account ID, so if an account has several cards, every
  interest transaction goes to whichever card the alternate index returns first.
- **Rate lookup uses `ACCT-GROUP-ID` as stored.** A blank or unknown group falls back to `DEFAULT` with only a log
  message.
- **No duplicate-run protection.** `PARM-DATE` is not checked, so running the job twice in a month charges interest
  twice.
- **Fees not implemented** (`1400-COMPUTE-FEES` is empty).
- **Record counter unused.** `WS-RECORD-COUNT` is incremented but never displayed.
