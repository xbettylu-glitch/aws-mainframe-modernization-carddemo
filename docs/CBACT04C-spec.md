# CBACT04C – Monthly Interest Calculation: Business Specification

| Item | Value |
|---|---|
| Program | `app/cbl/CBACT04C.cbl` (batch COBOL) |
| Job | `app/jcl/INTCALC.jcl`, step `STEP15` |
| Copybooks | `CVTRA01Y` (category balance), `CVACT03Y` (card cross-reference), `CVTRA02Y` (disclosure group / interest rate), `CVACT01Y` (account), `CVTRA05Y` (transaction) |

## 1. Purpose

Once a month, the program works out the interest owed on every account. Each account's balance is split into
**transaction categories** (for example, purchases or cash advances), and each category can have its own annual
interest rate. For each category the program:

1. looks up the annual rate that applies to the account's **disclosure group** (pricing group),
2. calculates one month of interest on that category's balance,
3. writes an interest transaction, and
4. adds the total interest for all categories to the account's current balance.

Fees are planned but not implemented: paragraph `1400-COMPUTE-FEES` is empty (`* To be implemented`).

## 2. Inputs

### 2.1 Run parameter

| Field | Format | Use |
|---|---|---|
| `PARM-DATE` | 10 characters (JCL: `PARM='2022071800'`) | Used only as the first 10 characters of every generated transaction ID. It is not checked and not used as a date in any calculation. |

### 2.2 Files

| DD name | File | Access | Record (copybook) | Fields used |
|---|---|---|---|---|
| `TCATBALF` | Transaction category balances (VSAM KSDS) | Input, read sequentially in key order | `TRAN-CAT-BAL-RECORD` (`CVTRA01Y`), 50 bytes | Key = `TRANCAT-ACCT-ID` 9(11) + `TRANCAT-TYPE-CD` X(2) + `TRANCAT-CD` 9(4); `TRAN-CAT-BAL` S9(9)V99 |
| `ACCTFILE` | Account master (VSAM KSDS) | Read and update, random by `ACCT-ID` | `ACCOUNT-RECORD` (`CVACT01Y`), 300 bytes | `ACCT-GROUP-ID` (read); `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT` (updated) |
| `XREFFILE` (+ AIX path `XREFFIL1`) | Card cross-reference (VSAM KSDS) | Input, random by **alternate key** `XREF-ACCT-ID` | `CARD-XREF-RECORD` (`CVACT03Y`), 50 bytes | `XREF-CARD-NUM` (copied to the interest transaction) |
| `DISCGRP` | Disclosure groups / interest rates (VSAM KSDS) | Input, random | `DIS-GROUP-RECORD` (`CVTRA02Y`), 50 bytes | Key = `DIS-ACCT-GROUP-ID` X(10) + `DIS-TRAN-TYPE-CD` X(2) + `DIS-TRAN-CAT-CD` 9(4); `DIS-INT-RATE` S9(4)V99 |

`DIS-INT-RATE` is an **annual percentage rate**: `18.99` means 18.99% a year.

## 3. Outputs

### 3.1 Interest transactions (`TRANSACT`)

A new sequential file (in the JCL, a new generation `AWS.M2.CARDDEMO.SYSTRAN(+1)`, fixed 350-byte records,
layout `TRAN-RECORD` from `CVTRA05Y`). The file is opened as `OUTPUT`, so it holds only the interest transactions
from this run. One record is written for each category balance whose rate is not zero:

| Field | Value |
|---|---|
| `TRAN-ID` (16) | `PARM-DATE` (10 chars) followed by a 6-digit counter (`WS-TRANID-SUFFIX`) that starts at `000001` and goes up by one for each transaction in the run, e.g. `2022071800000001` |
| `TRAN-TYPE-CD` | `'01'` |
| `TRAN-CAT-CD` | `0005` |
| `TRAN-SOURCE` | `'System'` |
| `TRAN-DESC` | `'Int. for a/c '` followed by the 11-digit account ID |
| `TRAN-AMT` | The monthly interest for this category (see section 4) |
| `TRAN-MERCHANT-ID` | `0` |
| `TRAN-MERCHANT-NAME` / `-CITY` / `-ZIP` | spaces |
| `TRAN-CARD-NUM` | `XREF-CARD-NUM` of the card found for the account |
| `TRAN-ORIG-TS`, `TRAN-PROC-TS` | Current system time, formatted `YYYY-MM-DD-HH.MM.SS.hh0000` (hundredths of a second, then four zeros) |

### 3.2 Account master updates (`ACCTFILE`)

When the program has finished all category records for an account, it rewrites the account record:

- `ACCT-CURR-BAL` = `ACCT-CURR-BAL` + total interest for the account (`WS-TOTAL-INT`)
- `ACCT-CURR-CYC-CREDIT` = 0
- `ACCT-CURR-CYC-DEBIT` = 0

The account is rewritten (and its cycle totals reset to zero) even when its total interest is zero, for example
when every category rate is 0%. Accounts with no category balance records are not read or changed.
**See section 7.1: the last account in the file is never rewritten.**

The category balance file is input only. `TRAN-CAT-BAL` is not changed.

### 3.3 Log output (SYSOUT)

- `START OF EXECUTION OF PROGRAM CBACT04C` / `END OF EXECUTION OF PROGRAM CBACT04C`
- Every category balance record, displayed exactly as it is stored
- Warning and error messages (section 6)

A record counter (`WS-RECORD-COUNT`) is kept but never displayed.

## 4. Business logic

### 4.1 Processing flow

```
open all files (an error opening any file stops the job)
for each category balance record, in key order (account, type, category):
    if the account ID is different from the previous record's:
        if this is not the first account: rewrite the previous account (3.2)
        total interest for account = 0
        read the account record by account ID
        read the card cross-reference by account ID (gives the card number)
    look up the rate with key (ACCT-GROUP-ID, TRANCAT-TYPE-CD, TRANCAT-CD)
        if there is no record: look up ('DEFAULT', TRANCAT-TYPE-CD, TRANCAT-CD)
    if the rate is not 0:
        monthly interest = formula in 4.2
        add the monthly interest to the account's total
        write an interest transaction (3.1)
        compute fees (not implemented, does nothing)
close all files
```

Because the input is read in key order, all categories for an account come one after another. The program relies
on this to know when it has finished an account.

### 4.2 Interest formula

```
monthly interest = (category balance × annual rate %) / 1200
```

In the code (`1300-COMPUTE-INTEREST`):

```cobol
COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

Dividing by 1200 is the same as dividing by 12 (months) and by 100 (percent to a fraction). It is simple interest
on the category balance at the time the program runs. There is no daily balance averaging, no day-count, no
compounding, no grace period, and no minimum charge.

- A **negative** category balance (a credit) gives **negative** interest, which lowers the account balance.
- A **zero** balance with a non-zero rate still writes a transaction for `0.00`.
- A rate of exactly `0` skips the category: no transaction is written.

### 4.3 Rounding

The result goes into `WS-MONTHLY-INT` (`PIC S9(09)V99`, two decimal places). The `COMPUTE` does **not** use
`ROUNDED`, so any digits after the second decimal place are **dropped (truncated toward zero)**, not rounded:

| Category balance | Annual rate | Exact result | Stored interest |
|---|---|---|---|
| 1,234.56 | 18.99 | 19.536912 | **19.53** (rounding would give 19.54) |
| -333.33 | 20.00 | -5.5555 | **-5.55** |

Each category is truncated on its own and then added up, so an account's total interest is the sum of the
truncated amounts. Over many categories this is a little lower than truncating the total once. The intermediate
precision of `COMPUTE` is set by the compiler. The final store always truncates to 2 decimals.

### 4.4 Overflow (field too large)

None of the arithmetic statements (`COMPUTE`, `ADD ... TO WS-TOTAL-INT`, `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`,
`ADD 1 TO WS-TRANID-SUFFIX`) has `ON SIZE ERROR`. If a result is too big for its field, the leading digits are
dropped without any warning:

- Interest and total interest fields hold up to 999,999,999.99; `ACCT-CURR-BAL` holds up to 9,999,999,999.99.
- The transaction ID counter has 6 digits. After 999,999 transactions in one run it goes back to `000000`, which
  can create duplicate `TRAN-ID`s.

## 5. Rate lookup rules (disclosure groups)

1. Read `DISCGRP` with the key (`ACCT-GROUP-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`).
2. If the record is found (file status `00`), use its `DIS-INT-RATE`.
3. If it is not found (file status `23`), log `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`,
   then read again with the group ID set to `'DEFAULT'` and the same type and category codes.
4. If the `DEFAULT` record is also missing, or any read returns another error status, the job stops (section 6).

So the `DISCGRP` file must have a `DEFAULT` record for every type/category combination that appears in the
balance file for an account whose own group has no record for it.

## 6. Error handling

Every file operation is checked. The only conditions handled without stopping the job are:

| Situation | File status | Result |
|---|---|---|
| End of the category balance file | `10` | Normal end of processing |
| No disclosure group record for the account's group | `23` | Fall back to the `DEFAULT` group (section 5) |

Every other non-`00` status stops the job the same way:

1. A message describing the problem is displayed (see the table below).
2. `9910-DISPLAY-IO-STATUS` displays `FILE STATUS IS: NNNN` followed by a 4-digit status. Normal statuses are
   shown as `00xx`. For non-numeric or `9x` statuses, the first digit is shown and then the second byte as a
   3-digit binary value.
3. `9999-ABEND-PROGRAM` displays `ABENDING PROGRAM` and calls `CEE3ABD` with abend code **999** (timing 0), which
   ends the job step abnormally.

| Operation | Message |
|---|---|
| Open category balance file | `ERROR OPENING TRANSACTION CATEGORY BALANCE` |
| Open cross-reference file | `ERROR OPENING CROSS REF FILE` + status |
| Open disclosure group file | `ERROR OPENING DALY REJECTS FILE` (wrong file name in the message; it is the disclosure group file) |
| Open account file | `ERROR OPENING ACCOUNT MASTER FILE` |
| Open transaction output file | `ERROR OPENING TRANSACTION FILE` |
| Read category balance file | `ERROR READING TRANSACTION CATEGORY FILE` |
| Account not found / read error | `ACCOUNT NOT FOUND: <id>` (if not found), then `ERROR READING ACCOUNT FILE` |
| Card cross-reference not found / read error | `ACCOUNT NOT FOUND: <id>` (if not found), then `ERROR READING XREF FILE` |
| Disclosure group read error (not `23`) | `ERROR READING DISCLOSURE GROUP FILE` |
| `DEFAULT` disclosure group missing or read error | `ERROR READING DEFAULT DISCLOSURE GROUP` |
| Write interest transaction | `ERROR WRITING TRANSACTION RECORD` |
| Rewrite account | `ERROR RE-WRITING ACCOUNT FILE` |
| Close any file | `ERROR CLOSING ...` (one message per file) |

There is no restart or checkpoint logic, and no rollback. If the job stops partway through, account records
already rewritten stay changed, and the transaction output file (created `DISP=(NEW,CATLG,DELETE)`) is deleted.
Before rerunning, the account master has to be restored, or interest will be charged twice on the accounts that
were already updated.

## 7. Known defects and observations

### 7.1 Interest for the last account is never added to the account balance

The main loop is `PERFORM UNTIL END-OF-FILE = 'Y'`, which checks the condition **before** each pass. On the pass
where the read reaches end of file, `END-OF-FILE` becomes `'Y'` and the loop exits before the next pass. The
`ELSE PERFORM 1050-UPDATE-ACCOUNT` branch, which is meant to save the last account, can never run.

Result: interest transactions **are** written for the last account in the file, but its `ACCT-CURR-BAL` is not
increased and its cycle credit/debit are not reset.

I confirmed this by compiling the unmodified program with GnuCOBOL 3.1.2 and running it on a small test data set
with two accounts. Account 1 went from 1000.00 to 1019.53 with its cycle totals reset to zero. Account 2 got an
interest transaction for -5.55 but stayed at 1000.00, with its cycle totals still 50.00.

### 7.2 Other observations

- **Only the first card is used.** The cross-reference is read by account ID. If an account has several cards,
  the interest transaction goes on whichever card the alternate index returns first.
- **Cycle totals reset only for accounts that have balances.** Accounts with no category balance records keep
  their cycle credit/debit values.
- **Fees are not implemented** (`1400-COMPUTE-FEES` is empty).
- **The run date is not checked.** `PARM-DATE` is used only for transaction IDs. Nothing stops the job from being
  run twice in the same month.
