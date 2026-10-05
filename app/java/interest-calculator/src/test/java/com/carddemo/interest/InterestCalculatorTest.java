package com.carddemo.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour of the job on small hand-built data sets. */
class InterestCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 18, 1, 2, 3, 450_000_000);

    @TempDir
    Path dir;

    private JobFiles files;
    private final ByteArrayOutputStream sysout = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        files = new JobFiles(dir.resolve("tcatbal.txt"), dir.resolve("cardxref.txt"), dir.resolve("acctdata.txt"),
                dir.resolve("discgrp.txt"), dir.resolve("transact.dat"));
        write(files.xref(), xref("4111111111111111", 1), xref("4222222222222222", 2));
        write(files.account(), account(1, "1000.00", "GOLD"), account(2, "1000.00", "SILVER"));
        write(files.discgrp(), disc("GOLD", "01", 1, "18.99"), disc("DEFAULT", "01", 1, "20.00"),
                disc("GOLD", "01", 2, "0.00"));
    }

    @Test
    void writesOneTruncatedInterestTransactionPerCategory() throws IOException {
        write(files.tcatbal(), tcat(1, "01", 1, "1234.56"), tcat(2, "01", 1, "-333.33"));

        assertEquals(0, run());

        List<String> tx = transactions();
        assertEquals(2, tx.size());
        assertEquals("2022071800000001", tx.get(0).substring(0, 16));
        assertEquals("01" + "0005" + "System    ", tx.get(0).substring(16, 32));
        assertEquals("Int. for a/c 00000000001", tx.get(0).substring(32, 56));
        assertEquals(ZonedDecimal.encode(new BigDecimalOf("19.53").value, 9, 2), tx.get(0).substring(132, 143));
        assertEquals("000000000", tx.get(0).substring(143, 152));
        assertEquals("4111111111111111", tx.get(0).substring(262, 278));
        assertEquals("2022-07-18-01.02.03.450000", tx.get(0).substring(278, 304));
        assertEquals("2022-07-18-01.02.03.450000", tx.get(0).substring(304, 330));
        // Account 2's group SILVER has no rate: falls back to DEFAULT (20%), -5.5555 -> -5.55.
        assertEquals("2022071800000002", tx.get(1).substring(0, 16));
        assertEquals(ZonedDecimal.encode(new BigDecimalOf("-5.55").value, 9, 2), tx.get(1).substring(132, 143));
        assertTrue(sysout().contains("DISCLOSURE GROUP RECORD MISSING\nTRY WITH DEFAULT GROUP CODE\n"));
    }

    @Test
    void updatesBalancesButNeverTheLastAccount() throws IOException {
        write(files.tcatbal(), tcat(1, "01", 1, "1234.56"), tcat(2, "01", 1, "-333.33"));

        run();

        List<String> accounts = Files.readAllLines(files.account(), StandardCharsets.ISO_8859_1);
        AccountRecord first = new AccountRecord();
        first.load(accounts.get(0));
        assertEquals(new BigDecimalOf("1019.53").value, first.currBal());
        assertEquals(ZonedDecimal.encode(new BigDecimalOf("0").value, 10, 2), accounts.get(0).substring(78, 90));
        // COBOL defect preserved: the last account in TCATBALF keeps its old balance and cycle totals.
        assertEquals(account(2, "1000.00", "SILVER"), accounts.get(1));
    }

    @Test
    void zeroRateCategoryWritesNoTransaction() throws IOException {
        write(files.tcatbal(), tcat(1, "01", 2, "5000.00"), tcat(2, "01", 1, "10.00"));

        run();

        assertEquals(1, transactions().size());
        assertTrue(transactions().get(0).startsWith("2022071800000001"));
    }

    @Test
    void missingDefaultGroupAbends() throws IOException {
        // Category 02/0001 has no GOLD rate and no DEFAULT rate either.
        write(files.tcatbal(), tcat(1, "01", 1, "1234.56"), tcat(1, "02", 1, "600.00"));

        assertEquals(InterestCalculator.ABEND_CODE, run());

        assertEquals(1, transactions().size());
        assertTrue(sysout().endsWith("DISCLOSURE GROUP RECORD MISSING\nTRY WITH DEFAULT GROUP CODE\n"
                + "ERROR READING DEFAULT DISCLOSURE GROUP\nFILE STATUS IS: NNNN0023\nABENDING PROGRAM\n"));
    }

    @Test
    void missingAccountAbendsWith999() throws IOException {
        write(files.tcatbal(), tcat(1, "01", 1, "100.00"), tcat(9, "01", 1, "100.00"));

        assertEquals(InterestCalculator.ABEND_CODE, run());

        assertTrue(sysout().endsWith("ACCOUNT NOT FOUND: 00000000009\nERROR READING ACCOUNT FILE\n"
                + "FILE STATUS IS: NNNN0023\nABENDING PROGRAM\n"));
        // The first account was rewritten before the abend, and the file was closed (saved).
        AccountRecord first = new AccountRecord();
        first.load(Files.readAllLines(files.account(), StandardCharsets.ISO_8859_1).get(0));
        assertEquals(new BigDecimalOf("1001.58").value, first.currBal());
    }

    @Test
    void missingInputFileAbendsOnOpen() throws IOException {
        write(files.tcatbal(), tcat(1, "01", 1, "100.00"));
        Files.delete(files.discgrp());

        assertEquals(InterestCalculator.ABEND_CODE, run());

        assertEquals("START OF EXECUTION OF PROGRAM CBACT04C\nERROR OPENING DALY REJECTS FILE\n"
                + "FILE STATUS IS: NNNN0035\nABENDING PROGRAM\n", sysout());
    }

    @Test
    void emptyBalanceFileWritesNothing() throws IOException {
        write(files.tcatbal());

        assertEquals(0, run());

        assertEquals(0, Files.size(files.transact()));
        assertEquals("START OF EXECUTION OF PROGRAM CBACT04C\nEND OF EXECUTION OF PROGRAM CBACT04C\n", sysout());
    }

    private int run() {
        return new InterestCalculator(files, "2022071800", () -> NOW,
                new PrintStream(sysout, true, StandardCharsets.ISO_8859_1)).run();
    }

    private String sysout() {
        return sysout.toString(StandardCharsets.ISO_8859_1);
    }

    private List<String> transactions() throws IOException {
        String all = Files.readString(files.transact(), StandardCharsets.ISO_8859_1);
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < all.length(); i += TransactionRecord.LENGTH) {
            out.add(all.substring(i, i + TransactionRecord.LENGTH));
        }
        return out;
    }

    private static void write(Path file, String... records) throws IOException {
        Files.writeString(file, records.length == 0 ? "" : String.join("\n", records) + "\n",
                StandardCharsets.ISO_8859_1);
    }

    private static String tcat(long acct, String type, int cat, String bal) {
        return String.format("%011d%s%04d%s", acct, type, cat, z(bal, 9)) + "0".repeat(22);
    }

    private static String disc(String group, String type, int cat, String rate) {
        return String.format("%-10s%s%04d%s", group, type, cat, z(rate, 4)) + "0".repeat(28);
    }

    private static String xref(String card, long acct) {
        return String.format("%s%09d%011d", card, acct, acct) + " ".repeat(14);
    }

    private static String account(long id, String bal, String group) {
        String rec = String.format("%011dY%s%s%s2014-11-202025-05-202025-05-20%s%s%-10s%-10s", id, z(bal, 10),
                z("5000", 10), z("1000", 10), z("12.34", 10), z("-56.78", 10), "12345", group);
        return rec + " ".repeat(AccountRecord.LENGTH - rec.length());
    }

    private static String z(String value, int intDigits) {
        return ZonedDecimal.encode(new BigDecimalOf(value).value, intDigits, 2);
    }

    private static final class BigDecimalOf {
        final java.math.BigDecimal value;

        BigDecimalOf(String s) {
            value = new java.math.BigDecimal(s).setScale(2);
        }
    }
}
