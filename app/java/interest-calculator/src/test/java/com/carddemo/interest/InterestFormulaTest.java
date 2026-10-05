package com.carddemo.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class InterestFormulaTest {

    @ParameterizedTest(name = "{0} x {1}% / 1200 = {2}")
    @CsvSource({
        "1234.56, 18.99, 19.53",       // 19.536912: truncated, not rounded up
        "-333.33, 20.00, -5.55",       // -5.5555: truncated toward zero, not to -5.56
        "1000.00, 23.50, 19.58",       // 19.583333
        "0.07, 15.25, 0.00",           // below one cent
        "-0.07, 15.25, 0.00",
        "500.00, -3.33, -1.38",        // negative rate
        "999999999.99, 9999.99, 333324999.91", // 8333324999.91 overflows S9(9)V99: leading digits dropped
        "0.00, 24.99, 0.00",
    })
    void monthlyInterestTruncatesLikeCobol(String balance, String rate, String expected) {
        assertEquals(new BigDecimal(expected),
                InterestCalculator.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate)));
    }

    @Test
    void db2TimestampFormat() {
        assertEquals("2022-07-18-01.02.03.450000",
                InterestCalculator.db2FormatTimestamp(LocalDateTime.of(2022, 7, 18, 1, 2, 3, 459_000_000)));
    }

    @ParameterizedTest
    @CsvSource({
        "23, FILE STATUS IS: NNNN0023",
        "35, FILE STATUS IS: NNNN0035",
        "9A, FILE STATUS IS: NNNN9065",
    })
    void ioStatusMessage(String status, String expected) {
        assertEquals(expected, InterestCalculator.formatIoStatus(status));
    }
}
