package com.carddemo.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ZonedDecimalTest {

    @ParameterizedTest
    @CsvSource({
        "0000000000{, 0.00",
        "0000194000{, 19400.00",
        "0000000145F, 14.56",
        "0000000707P, -70.77",
        "0000007630}, -763.00",
        "00000001940, 19.40",
        "0000000123u, -12.35",
    })
    void decodesOverpunchedSigns(String field, String expected) {
        assertEquals(new BigDecimal(expected), ZonedDecimal.decode(field, 2));
    }

    @ParameterizedTest
    @CsvSource({
        "0.00, 0000000000{",
        "14.56, 0000000145F",
        "-70.77, 0000000707P",
        "-763.00, 0000007630}",
        "-0.00, 0000000000{",
    })
    void encodesWithEbcdicOverpunch(String value, String expected) {
        assertEquals(expected, ZonedDecimal.encode(new BigDecimal(value), 9, 2));
    }

    @Test
    void roundTripsEveryLastDigitAndSign() {
        for (int units = -25; units <= 25; units++) {
            BigDecimal v = BigDecimal.valueOf(units, 2);
            assertEquals(v, ZonedDecimal.decode(ZonedDecimal.encode(v, 4, 2), 2));
        }
    }

    @Test
    void fitTruncatesDecimalsTowardZero() {
        assertEquals(new BigDecimal("19.53"), ZonedDecimal.fit(new BigDecimal("19.536912"), 9, 2));
        assertEquals(new BigDecimal("-5.55"), ZonedDecimal.fit(new BigDecimal("-5.5555"), 9, 2));
        assertEquals(new BigDecimal("0.99"), ZonedDecimal.fit(new BigDecimal("0.999999"), 9, 2));
    }

    @Test
    void fitDropsHighOrderDigitsOnOverflow() {
        assertEquals(new BigDecimal("234567890.12"), ZonedDecimal.fit(new BigDecimal("1234567890.129"), 9, 2));
        assertEquals(new BigDecimal("-234567890.12"), ZonedDecimal.fit(new BigDecimal("-1234567890.129"), 9, 2));
        assertEquals(new BigDecimal("0.00"), ZonedDecimal.fit(new BigDecimal("1000000000.00"), 9, 2));
    }

    @Test
    void unsignedFieldIsZeroPaddedAndTruncatedOnTheLeft() {
        assertEquals("000042", ZonedDecimal.unsigned(42, 6));
        assertEquals("000000", ZonedDecimal.unsigned(1_000_000, 6));
    }

    @Test
    void rejectsNonNumericData() {
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("00000A0000{", 2));
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("0000000000#", 2));
    }
}
