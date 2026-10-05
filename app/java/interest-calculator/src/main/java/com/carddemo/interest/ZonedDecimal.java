package com.carddemo.interest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Signed zoned-decimal ({@code PIC S9(n)V9(m)} DISPLAY) fields as they appear in the ASCII
 * CardDemo data: the sign is overpunched on the last digit using the EBCDIC letters
 * ({@code '{'}, {@code 'A'}-{@code 'I'} positive; {@code '}'}, {@code 'J'}-{@code 'R'} negative).
 *
 * <p>{@link #fit} reproduces how COBOL stores an arithmetic result without {@code ROUNDED} or
 * {@code ON SIZE ERROR}: extra decimal places are truncated toward zero and extra leading
 * integer digits are dropped.
 */
public final class ZonedDecimal {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private ZonedDecimal() {
    }

    /** Decodes a signed zoned-decimal field with {@code scale} implied decimal places. */
    public static BigDecimal decode(String field, int scale) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("empty numeric field");
        }
        StringBuilder digits = new StringBuilder(field.length());
        for (int i = 0; i < field.length() - 1; i++) {
            char c = field.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("non-numeric data in zoned decimal field: '" + field + "'");
            }
            digits.append(c);
        }
        char last = field.charAt(field.length() - 1);
        boolean negative;
        int pos;
        if (last >= '0' && last <= '9') {
            digits.append(last);
            negative = false;
        } else if ((pos = POSITIVE.indexOf(last)) >= 0) {
            digits.append((char) ('0' + pos));
            negative = false;
        } else if ((pos = NEGATIVE.indexOf(last)) >= 0) {
            digits.append((char) ('0' + pos));
            negative = true;
        } else if (last >= 'p' && last <= 'y') {
            // ASCII-style negative overpunch, accepted for robustness.
            digits.append((char) ('0' + (last - 'p')));
            negative = true;
        } else {
            throw new IllegalArgumentException("invalid sign in zoned decimal field: '" + field + "'");
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return negative ? value.negate() : value;
    }

    /**
     * Stores {@code value} into a {@code PIC S9(integerDigits)V9(scale)} field the way COBOL does
     * without {@code ROUNDED} or {@code ON SIZE ERROR}: truncate decimals toward zero, then drop
     * high-order integer digits that do not fit.
     */
    public static BigDecimal fit(BigDecimal value, int integerDigits, int scale) {
        BigDecimal truncated = value.setScale(scale, RoundingMode.DOWN);
        BigDecimal modulus = BigDecimal.TEN.pow(integerDigits);
        BigDecimal result = truncated.remainder(modulus).setScale(scale, RoundingMode.UNNECESSARY);
        return result.signum() == 0 ? result.abs() : result;
    }

    /** Encodes {@code value} (after {@link #fit}) as a signed zoned-decimal field. */
    public static String encode(BigDecimal value, int integerDigits, int scale) {
        BigDecimal fitted = fit(value, integerDigits, scale);
        int width = integerDigits + scale;
        String digits = fitted.unscaledValue().abs().toString();
        StringBuilder out = new StringBuilder(width);
        for (int i = digits.length(); i < width; i++) {
            out.append('0');
        }
        out.append(digits, 0, digits.length() - 1);
        int lastDigit = digits.charAt(digits.length() - 1) - '0';
        out.append(fitted.signum() < 0 ? NEGATIVE.charAt(lastDigit) : POSITIVE.charAt(lastDigit));
        return out.toString();
    }

    /** Encodes a non-negative integer into an unsigned {@code PIC 9(digits)} field. */
    public static String unsigned(long value, int digits) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value for unsigned field: " + value);
        }
        String s = Long.toString(value);
        if (s.length() > digits) {
            return s.substring(s.length() - digits);
        }
        return "0".repeat(digits - s.length()) + s;
    }
}
