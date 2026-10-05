package com.carddemo.interest;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Java port of the CardDemo batch program {@code CBACT04C} (JCL {@code INTCALC}): computes
 * monthly interest per transaction-category balance, writes one interest transaction per
 * category and adds the total to the account's current balance.
 *
 * <p>The port is deliberately literal so that its outputs match the COBOL byte for byte. Each
 * method names the COBOL paragraph it replaces. Known COBOL quirks are kept on purpose, including
 * truncation instead of rounding and the last account never being rewritten (see
 * {@link #processCategoryBalances()}).
 */
public final class InterestCalculator {

    /** {@code MOVE 999 TO ABCODE} in {@code 9999-ABEND-PROGRAM}. */
    public static final int ABEND_CODE = 999;

    private static final BigDecimal MONTHS_TIMES_PERCENT = new BigDecimal(1200);

    private final String parmDate;
    private final Supplier<LocalDateTime> clock;
    private final PrintStream sysout;

    private final IndexedFile tcatbalFile;
    private final IndexedFile xrefFile;
    private final IndexedFile discgrpFile;
    private final IndexedFile accountFile;
    private final SequentialOutputFile transactFile;

    // WORKING-STORAGE
    private final TranCatBalRecord tranCatBal = new TranCatBalRecord();
    private final CardXrefRecord cardXref = new CardXrefRecord();
    private final DisclosureGroupRecord disGroup = new DisclosureGroupRecord();
    private final AccountRecord account = new AccountRecord();
    private final TransactionRecord tran = new TransactionRecord();
    private boolean endOfFile;
    private String lastAcctNum = " ".repeat(11);
    private BigDecimal monthlyInt = BigDecimal.ZERO.setScale(2);
    private BigDecimal totalInt = BigDecimal.ZERO.setScale(2);
    private boolean firstTime = true;
    private long recordCount;
    private long tranIdSuffix;

    // Key fields of the FD records (FD-ACCT-ID, FD-XREF-ACCT-ID, FD-DIS-GROUP-KEY parts).
    private String fdAcctId;
    private String fdXrefAcctId;
    private String fdDisAcctGroupId;
    private String fdDisTranTypeCd;
    private String fdDisTranCatCd;

    /**
     * @param files     the job's datasets (DD names TCATBALF, XREFFILE, ACCTFILE, DISCGRP, TRANSACT)
     * @param parmDate  JCL {@code PARM} ({@code PARM-DATE PIC X(10)}), prefix of generated TRAN-IDs
     * @param clock     source of {@code FUNCTION CURRENT-DATE}
     * @param sysout    destination of {@code DISPLAY} output
     */
    public InterestCalculator(JobFiles files, String parmDate, Supplier<LocalDateTime> clock, PrintStream sysout) {
        this.parmDate = pad(parmDate, 10);
        this.clock = clock;
        this.sysout = sysout;
        this.tcatbalFile = new IndexedFile(files.tcatbal(), TranCatBalRecord.LENGTH, TranCatBalRecord::keyOf);
        this.xrefFile = new IndexedFile(files.xref(), CardXrefRecord.LENGTH, CardXrefRecord::keyOf,
                CardXrefRecord::acctIdOf);
        this.discgrpFile = new IndexedFile(files.discgrp(), DisclosureGroupRecord.LENGTH,
                DisclosureGroupRecord::keyOf);
        this.accountFile = new IndexedFile(files.account(), AccountRecord.LENGTH, AccountRecord::keyOf);
        this.transactFile = new SequentialOutputFile(files.transact());
    }

    /**
     * Runs the job (PROCEDURE DIVISION). Returns 0 on normal completion, or {@link #ABEND_CODE}
     * after {@code 9999-ABEND-PROGRAM}; open files are closed either way, as LE does on an abend.
     */
    public int run() {
        try {
            mainline();
            return 0;
        } catch (AbendException e) {
            closeOpenFilesAfterAbend();
            return e.abendCode();
        } finally {
            sysout.flush();
        }
    }

    /** PROCEDURE DIVISION mainline. */
    private void mainline() {
        display("START OF EXECUTION OF PROGRAM CBACT04C");
        openTcatbalFile();
        openXrefFile();
        openDiscgrpFile();
        openAccountFile();
        openTransactFile();
        processCategoryBalances();
        closeTcatbalFile();
        closeXrefFile();
        closeDiscgrpFile();
        closeAccountFile();
        closeTransactFile();
        display("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    /**
     * The {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop of the mainline.
     *
     * <p>COBOL defect kept on purpose: the {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} branch that
     * should save the last account is unreachable, because the loop condition is tested before
     * each pass and stops as soon as end of file is reached. So the last account's interest
     * transactions are written but its balance is never updated. Do not "fix" this here without
     * changing the COBOL contract.
     */
    private void processCategoryBalances() {
        while (!endOfFile) {
            if (!endOfFile) {
                getNextCategoryBalance();
                if (!endOfFile) {
                    recordCount = (recordCount + 1) % 1_000_000_000L;
                    display(tranCatBal.raw());
                    if (!tranCatBal.acctId().equals(lastAcctNum)) {
                        if (!firstTime) {
                            updateAccount();
                        } else {
                            firstTime = false;
                        }
                        totalInt = BigDecimal.ZERO.setScale(2);
                        lastAcctNum = tranCatBal.acctId();
                        fdAcctId = tranCatBal.acctId();
                        getAccountData();
                        fdXrefAcctId = tranCatBal.acctId();
                        getXrefData();
                    }
                    fdDisAcctGroupId = account.groupId();
                    fdDisTranCatCd = tranCatBal.catCd();
                    fdDisTranTypeCd = tranCatBal.typeCd();
                    getInterestRate();
                    if (disGroup.intRate().signum() != 0) {
                        computeInterest();
                        computeFees();
                    }
                }
            } else {
                updateAccount(); // unreachable, as in the COBOL
            }
        }
    }

    /** 0000-TCATBALF-OPEN. */
    private void openTcatbalFile() {
        tcatbalFile.open(IndexedFile.OpenMode.INPUT);
        if (!IndexedFile.OK.equals(tcatbalFile.status())) {
            display("ERROR OPENING TRANSACTION CATEGORY BALANCE");
            abendWithStatus(tcatbalFile.status());
        }
    }

    /** 0100-XREFFILE-OPEN. */
    private void openXrefFile() {
        xrefFile.open(IndexedFile.OpenMode.INPUT);
        if (!IndexedFile.OK.equals(xrefFile.status())) {
            display("ERROR OPENING CROSS REF FILE" + xrefFile.status());
            abendWithStatus(xrefFile.status());
        }
    }

    /** 0200-DISCGRP-OPEN. */
    private void openDiscgrpFile() {
        discgrpFile.open(IndexedFile.OpenMode.INPUT);
        if (!IndexedFile.OK.equals(discgrpFile.status())) {
            display("ERROR OPENING DALY REJECTS FILE");
            abendWithStatus(discgrpFile.status());
        }
    }

    /** 0300-ACCTFILE-OPEN. */
    private void openAccountFile() {
        accountFile.open(IndexedFile.OpenMode.I_O);
        if (!IndexedFile.OK.equals(accountFile.status())) {
            display("ERROR OPENING ACCOUNT MASTER FILE");
            abendWithStatus(accountFile.status());
        }
    }

    /** 0400-TRANFILE-OPEN. */
    private void openTransactFile() {
        transactFile.open();
        if (!IndexedFile.OK.equals(transactFile.status())) {
            display("ERROR OPENING TRANSACTION FILE");
            abendWithStatus(transactFile.status());
        }
    }

    /** 1000-TCATBALF-GET-NEXT. */
    private void getNextCategoryBalance() {
        String raw = tcatbalFile.readNext();
        String status = tcatbalFile.status();
        if (IndexedFile.OK.equals(status)) {
            tranCatBal.load(raw);
        } else if (IndexedFile.END_OF_FILE.equals(status)) {
            endOfFile = true;
        } else {
            display("ERROR READING TRANSACTION CATEGORY FILE");
            abendWithStatus(status);
        }
    }

    /** 1050-UPDATE-ACCOUNT. */
    private void updateAccount() {
        account.setCurrBal(account.currBal().add(totalInt));
        account.setCurrCycCredit(BigDecimal.ZERO);
        account.setCurrCycDebit(BigDecimal.ZERO);
        accountFile.rewrite(account.raw());
        if (!IndexedFile.OK.equals(accountFile.status())) {
            display("ERROR RE-WRITING ACCOUNT FILE");
            abendWithStatus(accountFile.status());
        }
    }

    /** 1100-GET-ACCT-DATA. */
    private void getAccountData() {
        String raw = accountFile.read(fdAcctId);
        if (IndexedFile.RECORD_NOT_FOUND.equals(accountFile.status())) {
            display("ACCOUNT NOT FOUND: " + fdAcctId);
        }
        if (IndexedFile.OK.equals(accountFile.status())) {
            account.load(raw);
        } else {
            display("ERROR READING ACCOUNT FILE");
            abendWithStatus(accountFile.status());
        }
    }

    /** 1110-GET-XREF-DATA (read by the alternate key XREF-ACCT-ID). */
    private void getXrefData() {
        String raw = xrefFile.readByAlternateKey(fdXrefAcctId);
        if (IndexedFile.RECORD_NOT_FOUND.equals(xrefFile.status())) {
            display("ACCOUNT NOT FOUND: " + fdXrefAcctId);
        }
        if (IndexedFile.OK.equals(xrefFile.status())) {
            cardXref.load(raw);
        } else {
            display("ERROR READING XREF FILE");
            abendWithStatus(xrefFile.status());
        }
    }

    /** 1200-GET-INTEREST-RATE: falls back to group 'DEFAULT' if the account's group has no rate. */
    private void getInterestRate() {
        String raw = discgrpFile.read(disGroupKey());
        String status = discgrpFile.status();
        if (IndexedFile.RECORD_NOT_FOUND.equals(status)) {
            display("DISCLOSURE GROUP RECORD MISSING");
            display("TRY WITH DEFAULT GROUP CODE");
        }
        if (IndexedFile.OK.equals(status)) {
            disGroup.load(raw);
        } else if (!IndexedFile.RECORD_NOT_FOUND.equals(status)) {
            display("ERROR READING DISCLOSURE GROUP FILE");
            abendWithStatus(status);
        }
        if (IndexedFile.RECORD_NOT_FOUND.equals(status)) {
            fdDisAcctGroupId = pad("DEFAULT", 10);
            getDefaultInterestRate();
        }
    }

    /** 1200-A-GET-DEFAULT-INT-RATE. */
    private void getDefaultInterestRate() {
        String raw = discgrpFile.read(disGroupKey());
        if (IndexedFile.OK.equals(discgrpFile.status())) {
            disGroup.load(raw);
        } else {
            display("ERROR READING DEFAULT DISCLOSURE GROUP");
            abendWithStatus(discgrpFile.status());
        }
    }

    /**
     * 1300-COMPUTE-INTEREST: {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     * No ROUNDED clause, so the result is truncated toward zero to two decimals.
     */
    private void computeInterest() {
        monthlyInt = monthlyInterest(tranCatBal.balance(), disGroup.intRate());
        totalInt = ZonedDecimal.fit(totalInt.add(monthlyInt), 9, 2);
        writeInterestTransaction();
    }

    /**
     * The interest formula as stored in {@code WS-MONTHLY-INT PIC S9(09)V99}: truncated (not
     * rounded) to cents, toward zero for negative balances too.
     */
    static BigDecimal monthlyInterest(BigDecimal categoryBalance, BigDecimal annualRatePercent) {
        BigDecimal exact = categoryBalance.multiply(annualRatePercent)
                .divide(MONTHS_TIMES_PERCENT, 2, RoundingMode.DOWN);
        return ZonedDecimal.fit(exact, 9, 2);
    }

    /** 1300-B-WRITE-TX. */
    private void writeInterestTransaction() {
        tranIdSuffix = (tranIdSuffix + 1) % 1_000_000L;
        tran.setTranId(parmDate + ZonedDecimal.unsigned(tranIdSuffix, 6));
        tran.setTypeCd("01");
        tran.setCatCd(5);
        tran.setSource("System");
        tran.stringIntoDesc("Int. for a/c " + account.acctId());
        tran.setAmt(monthlyInt);
        tran.setMerchantId(0);
        tran.setMerchantName("");
        tran.setMerchantCity("");
        tran.setMerchantZip("");
        tran.setCardNum(cardXref.cardNum());
        String timestamp = db2FormatTimestamp();
        tran.setOrigTs(timestamp);
        tran.setProcTs(timestamp);
        transactFile.write(tran.raw());
        if (!IndexedFile.OK.equals(transactFile.status())) {
            display("ERROR WRITING TRANSACTION RECORD");
            abendWithStatus(transactFile.status());
        }
    }

    /** 1400-COMPUTE-FEES: "To be implemented" in the COBOL; intentionally does nothing. */
    private void computeFees() {
        // No fee logic in CBACT04C.
    }

    /** 9000-TCATBALF-CLOSE. */
    private void closeTcatbalFile() {
        tcatbalFile.close();
        if (!IndexedFile.OK.equals(tcatbalFile.status())) {
            display("ERROR CLOSING TRANSACTION BALANCE FILE");
            abendWithStatus(tcatbalFile.status());
        }
    }

    /** 9100-XREFFILE-CLOSE. */
    private void closeXrefFile() {
        xrefFile.close();
        if (!IndexedFile.OK.equals(xrefFile.status())) {
            display("ERROR CLOSING CROSS REF FILE");
            abendWithStatus(xrefFile.status());
        }
    }

    /** 9200-DISCGRP-CLOSE. */
    private void closeDiscgrpFile() {
        discgrpFile.close();
        if (!IndexedFile.OK.equals(discgrpFile.status())) {
            display("ERROR CLOSING DISCLOSURE GROUP FILE");
            abendWithStatus(discgrpFile.status());
        }
    }

    /** 9300-ACCTFILE-CLOSE. */
    private void closeAccountFile() {
        accountFile.close();
        if (!IndexedFile.OK.equals(accountFile.status())) {
            display("ERROR CLOSING ACCOUNT FILE");
            abendWithStatus(accountFile.status());
        }
    }

    /** 9400-TRANFILE-CLOSE. */
    private void closeTransactFile() {
        transactFile.close();
        if (!IndexedFile.OK.equals(transactFile.status())) {
            display("ERROR CLOSING TRANSACTION FILE");
            abendWithStatus(transactFile.status());
        }
    }

    /** Z-GET-DB2-FORMAT-TIMESTAMP: {@code YYYY-MM-DD-HH.MM.SS.hh0000}. */
    private String db2FormatTimestamp() {
        return db2FormatTimestamp(clock.get());
    }

    static String db2FormatTimestamp(LocalDateTime now) {
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000", now.getYear(), now.getMonthValue(),
                now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond(), now.getNano() / 10_000_000);
    }

    /** 9910-DISPLAY-IO-STATUS followed by 9999-ABEND-PROGRAM. */
    private void abendWithStatus(String ioStatus) {
        display(formatIoStatus(ioStatus));
        display("ABENDING PROGRAM");
        throw new AbendException(ABEND_CODE);
    }

    /** 9910-DISPLAY-IO-STATUS: the message text for a two-character file status. */
    static String formatIoStatus(String ioStatus) {
        char stat1 = ioStatus.charAt(0);
        char stat2 = ioStatus.charAt(1);
        boolean numeric = Character.isDigit(stat1) && Character.isDigit(stat2) && stat1 < 128 && stat2 < 128;
        if (!numeric || stat1 == '9') {
            // Second status byte shown as its binary value (e.g. VSAM return codes).
            return "FILE STATUS IS: NNNN" + stat1 + ZonedDecimal.unsigned((stat2 & 0xFF) % 1000, 3);
        }
        return "FILE STATUS IS: NNNN00" + ioStatus;
    }

    private void closeOpenFilesAfterAbend() {
        if (tcatbalFile.isOpen()) {
            tcatbalFile.close();
        }
        if (xrefFile.isOpen()) {
            xrefFile.close();
        }
        if (discgrpFile.isOpen()) {
            discgrpFile.close();
        }
        if (accountFile.isOpen()) {
            accountFile.close();
        }
        if (transactFile.isOpen()) {
            transactFile.close();
        }
    }

    private String disGroupKey() {
        return fdDisAcctGroupId + fdDisTranTypeCd + fdDisTranCatCd;
    }

    private void display(String line) {
        sysout.print(line);
        sysout.print('\n');
    }

    long recordCount() {
        return recordCount;
    }

    private static String pad(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }
}
