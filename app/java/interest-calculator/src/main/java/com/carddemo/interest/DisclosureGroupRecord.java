package com.carddemo.interest;

import java.math.BigDecimal;

/** {@code DIS-GROUP-RECORD} from copybook CVTRA02Y (50 bytes). */
final class DisclosureGroupRecord extends FixedRecord {

    static final int LENGTH = 50;
    static final int KEY_LENGTH = 16;

    DisclosureGroupRecord() {
        super(LENGTH);
    }

    /** {@code DIS-GROUP-KEY}: group id X(10) + type code X(2) + category code 9(4). */
    static String keyOf(String raw) {
        return raw.substring(0, KEY_LENGTH);
    }

    /** {@code DIS-INT-RATE PIC S9(04)V99}: annual percentage rate. */
    BigDecimal intRate() {
        return decimal(16, 4, 2);
    }
}
