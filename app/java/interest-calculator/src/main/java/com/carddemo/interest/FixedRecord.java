package com.carddemo.interest;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * A fixed-length record laid out like a COBOL copybook. The raw characters are kept, so any
 * bytes the program does not touch (FILLER, unused fields) are written back unchanged.
 */
abstract class FixedRecord {

    private final char[] data;

    protected FixedRecord(int length) {
        data = new char[length];
        Arrays.fill(data, ' ');
    }

    /** Replaces the whole record ({@code READ ... INTO}): short input is padded with spaces. */
    final void load(String raw) {
        Arrays.fill(data, ' ');
        raw.getChars(0, Math.min(raw.length(), data.length), data, 0);
    }

    final int length() {
        return data.length;
    }

    final String raw() {
        return new String(data);
    }

    final String text(int offset, int length) {
        return new String(data, offset, length);
    }

    /** Alphanumeric MOVE: left-justified, space-padded or truncated on the right. */
    final void setText(int offset, int length, String value) {
        for (int i = 0; i < length; i++) {
            data[offset + i] = i < value.length() ? value.charAt(i) : ' ';
        }
    }

    /** Writes characters without padding (like {@code STRING ... INTO}, which leaves the rest). */
    final void overlay(int offset, String value) {
        value.getChars(0, value.length(), data, offset);
    }

    final BigDecimal decimal(int offset, int integerDigits, int scale) {
        return ZonedDecimal.decode(text(offset, integerDigits + scale), scale);
    }

    final void setDecimal(int offset, int integerDigits, int scale, BigDecimal value) {
        overlay(offset, ZonedDecimal.encode(value, integerDigits, scale));
    }
}
