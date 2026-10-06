package com.carddemo.interest;

import java.math.BigDecimal;

/** {@code ACCOUNT-RECORD} from copybook CVACT01Y (300 bytes). */
final class AccountRecord extends FixedRecord {

    static final int LENGTH = 300;

    AccountRecord() {
        super(LENGTH);
    }

    /** Primary key {@code ACCT-ID PIC 9(11)}. */
    static String keyOf(String raw) {
        return raw.substring(0, 11);
    }

    String acctId() {
        return text(0, 11);
    }

    /** {@code ACCT-CURR-BAL PIC S9(10)V99}. */
    BigDecimal currBal() {
        return decimal(12, 10, 2);
    }

    void setCurrBal(BigDecimal value) {
        setDecimal(12, 10, 2, value);
    }

    /** {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}. */
    void setCurrCycCredit(BigDecimal value) {
        setDecimal(78, 10, 2, value);
    }

    /** {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}. */
    void setCurrCycDebit(BigDecimal value) {
        setDecimal(90, 10, 2, value);
    }

    /** {@code ACCT-GROUP-ID PIC X(10)}. */
    String groupId() {
        return text(112, 10);
    }
}
