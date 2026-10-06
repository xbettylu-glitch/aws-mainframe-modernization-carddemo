package com.carddemo.interest;

import java.nio.file.Path;

/**
 * The INTCALC job's datasets, by DD name.
 *
 * @param tcatbal  TCATBALF: transaction-category balances (input, read in key order)
 * @param xref     XREFFILE/XREFFIL1: card cross-reference (input, read by account id)
 * @param account  ACCTFILE: account master (updated in place)
 * @param discgrp  DISCGRP: disclosure groups / interest rates (input)
 * @param transact TRANSACT: generated interest transactions (output, 350-byte fixed records)
 */
public record JobFiles(Path tcatbal, Path xref, Path account, Path discgrp, Path transact) {
}
