package com.carddemo.interest;

/** {@code CARD-XREF-RECORD} from copybook CVACT03Y (50 bytes). */
final class CardXrefRecord extends FixedRecord {

    static final int LENGTH = 50;

    CardXrefRecord() {
        super(LENGTH);
    }

    /** Primary key {@code XREF-CARD-NUM PIC X(16)}. */
    static String keyOf(String raw) {
        return raw.substring(0, 16);
    }

    /** Alternate key {@code XREF-ACCT-ID PIC 9(11)}. */
    static String acctIdOf(String raw) {
        return raw.substring(25, 36);
    }

    String cardNum() {
        return text(0, 16);
    }

    String acctId() {
        return text(25, 11);
    }
}
