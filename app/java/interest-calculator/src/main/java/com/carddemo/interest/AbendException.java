package com.carddemo.interest;

/** Raised by {@code 9999-ABEND-PROGRAM}; the COBOL equivalent is {@code CALL 'CEE3ABD'}. */
final class AbendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int abendCode;

    AbendException(int abendCode) {
        super("ABEND U" + abendCode);
        this.abendCode = abendCode;
    }

    int abendCode() {
        return abendCode;
    }
}
