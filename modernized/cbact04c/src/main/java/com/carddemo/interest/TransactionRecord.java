package com.carddemo.interest;

import java.math.BigDecimal;

/** {@code TRAN-RECORD} from copybook CVTRA05Y (350 bytes). */
final class TransactionRecord extends FixedRecord {

    static final int LENGTH = 350;

    TransactionRecord() {
        super(LENGTH);
    }

    void setTranId(String value) {
        setText(0, 16, value);
    }

    void setTypeCd(String value) {
        setText(16, 2, value);
    }

    /** {@code TRAN-CAT-CD PIC 9(04)}. */
    void setCatCd(long value) {
        overlay(18, ZonedDecimal.unsigned(value, 4));
    }

    void setSource(String value) {
        setText(22, 10, value);
    }

    /** {@code STRING ... INTO TRAN-DESC}: only the characters produced are replaced. */
    void stringIntoDesc(String value) {
        overlay(32, value.substring(0, Math.min(value.length(), 100)));
    }

    /** {@code TRAN-AMT PIC S9(09)V99}. */
    void setAmt(BigDecimal value) {
        setDecimal(132, 9, 2, value);
    }

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    void setMerchantId(long value) {
        overlay(143, ZonedDecimal.unsigned(value, 9));
    }

    void setMerchantName(String value) {
        setText(152, 50, value);
    }

    void setMerchantCity(String value) {
        setText(202, 50, value);
    }

    void setMerchantZip(String value) {
        setText(252, 10, value);
    }

    void setCardNum(String value) {
        setText(262, 16, value);
    }

    void setOrigTs(String value) {
        setText(278, 26, value);
    }

    void setProcTs(String value) {
        setText(304, 26, value);
    }
}
