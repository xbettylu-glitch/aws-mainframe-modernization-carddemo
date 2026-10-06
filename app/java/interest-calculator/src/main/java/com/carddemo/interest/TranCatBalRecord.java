package com.carddemo.interest;

import java.math.BigDecimal;

/** {@code TRAN-CAT-BAL-RECORD} from copybook CVTRA01Y (50 bytes). */
final class TranCatBalRecord extends FixedRecord {

    static final int LENGTH = 50;
    static final int KEY_LENGTH = 17;

    TranCatBalRecord() {
        super(LENGTH);
    }

    /** {@code TRAN-CAT-KEY}: account id + type code + category code. */
    static String keyOf(String raw) {
        return raw.substring(0, KEY_LENGTH);
    }

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}. */
    String acctId() {
        return text(0, 11);
    }

    /** {@code TRANCAT-TYPE-CD PIC X(02)}. */
    String typeCd() {
        return text(11, 2);
    }

    /** {@code TRANCAT-CD PIC 9(04)}. */
    String catCd() {
        return text(13, 4);
    }

    /** {@code TRAN-CAT-BAL PIC S9(09)V99}. */
    BigDecimal balance() {
        return decimal(17, 9, 2);
    }
}
