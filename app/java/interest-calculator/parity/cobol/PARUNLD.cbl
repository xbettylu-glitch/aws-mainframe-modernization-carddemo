      ******************************************************************
      * PARUNLD - parity harness only (not part of CardDemo).
      * Unloads an indexed file to fixed-length LF-terminated lines.
      * Usage: parunld ACCT | TCAT   (DD_ACCTFILE/DD_TCATBALF, DD_UNLOUT)
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. PARUNLD.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT ACCT-KS ASSIGN TO ACCTFILE
                  ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
                  RECORD KEY IS AK-ID FILE STATUS IS WS-STAT.
           SELECT TCAT-KS ASSIGN TO TCATBALF
                  ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
                  RECORD KEY IS TK-KEY FILE STATUS IS WS-STAT.
           SELECT UNL-ACCT ASSIGN TO UNLOUT
                  ORGANIZATION IS SEQUENTIAL.
           SELECT UNL-TCAT ASSIGN TO UNLOUT
                  ORGANIZATION IS SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  ACCT-KS.
       01  AK-REC.
           05 AK-ID               PIC 9(11).
           05 FILLER              PIC X(289).
       FD  TCAT-KS.
       01  TK-REC.
           05 TK-KEY              PIC X(17).
           05 FILLER              PIC X(33).
       FD  UNL-ACCT.
       01  UA-REC.
           05 UA-DATA             PIC X(300).
           05 UA-LF               PIC X.
       FD  UNL-TCAT.
       01  UT-REC.
           05 UT-DATA             PIC X(50).
           05 UT-LF               PIC X.
       WORKING-STORAGE SECTION.
       01  WS-STAT                PIC XX.
       01  WS-EOF                 PIC X VALUE 'N'.
       01  WS-WHICH               PIC X(4).
       PROCEDURE DIVISION.
           ACCEPT WS-WHICH FROM COMMAND-LINE
           IF WS-WHICH = 'ACCT'
              OPEN INPUT ACCT-KS OUTPUT UNL-ACCT
              PERFORM UNTIL WS-EOF = 'Y'
                 READ ACCT-KS AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                    MOVE AK-REC TO UA-DATA MOVE X'0A' TO UA-LF
                    WRITE UA-REC
                 END-READ
              END-PERFORM
              CLOSE ACCT-KS UNL-ACCT
           ELSE
              OPEN INPUT TCAT-KS OUTPUT UNL-TCAT
              PERFORM UNTIL WS-EOF = 'Y'
                 READ TCAT-KS AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                    MOVE TK-REC TO UT-DATA MOVE X'0A' TO UT-LF
                    WRITE UT-REC
                 END-READ
              END-PERFORM
              CLOSE TCAT-KS UNL-TCAT
           END-IF
           STOP RUN.
