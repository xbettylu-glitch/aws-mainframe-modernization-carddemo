      ******************************************************************
      * PARLOAD - parity harness only (not part of CardDemo).
      * Loads the line-sequential sample files into GnuCOBOL indexed
      * files with the same keys CBACT04C/CBTRN02C declare (stands in
      * for the IDCAMS REPRO steps in the JCL).
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. PARLOAD.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TCAT-IN ASSIGN TO TCATIN
                  ORGANIZATION IS LINE SEQUENTIAL.
           SELECT XREF-IN ASSIGN TO XREFIN
                  ORGANIZATION IS LINE SEQUENTIAL.
           SELECT ACCT-IN ASSIGN TO ACCTIN
                  ORGANIZATION IS LINE SEQUENTIAL.
           SELECT DISC-IN ASSIGN TO DISCIN
                  ORGANIZATION IS LINE SEQUENTIAL.
           SELECT TCAT-KS ASSIGN TO TCATBALF
                  ORGANIZATION IS INDEXED ACCESS MODE IS DYNAMIC
                  RECORD KEY IS TK-KEY FILE STATUS IS WS-STAT.
           SELECT XREF-KS ASSIGN TO XREFFILE
                  ORGANIZATION IS INDEXED ACCESS MODE IS DYNAMIC
                  RECORD KEY IS XK-CARD
                  ALTERNATE RECORD KEY IS XK-ACCT
                  FILE STATUS IS WS-STAT.
           SELECT ACCT-KS ASSIGN TO ACCTFILE
                  ORGANIZATION IS INDEXED ACCESS MODE IS DYNAMIC
                  RECORD KEY IS AK-ID FILE STATUS IS WS-STAT.
           SELECT DISC-KS ASSIGN TO DISCGRP
                  ORGANIZATION IS INDEXED ACCESS MODE IS DYNAMIC
                  RECORD KEY IS DK-KEY FILE STATUS IS WS-STAT.
       DATA DIVISION.
       FILE SECTION.
       FD  TCAT-IN.
       01  TI-REC                 PIC X(50).
       FD  XREF-IN.
       01  XI-REC                 PIC X(50).
       FD  ACCT-IN.
       01  AI-REC                 PIC X(300).
       FD  DISC-IN.
       01  DI-REC                 PIC X(50).
       FD  TCAT-KS.
       01  TK-REC.
           05 TK-KEY              PIC X(17).
           05 FILLER              PIC X(33).
       FD  XREF-KS.
       01  XK-REC.
           05 XK-CARD             PIC X(16).
           05 FILLER              PIC X(09).
           05 XK-ACCT             PIC 9(11).
           05 FILLER              PIC X(14).
       FD  ACCT-KS.
       01  AK-REC.
           05 AK-ID               PIC 9(11).
           05 FILLER              PIC X(289).
       FD  DISC-KS.
       01  DK-REC.
           05 DK-KEY              PIC X(16).
           05 FILLER              PIC X(34).
       WORKING-STORAGE SECTION.
       01  WS-STAT                PIC XX.
       01  WS-EOF                 PIC X.
       01  WS-COUNT               PIC 9(7).
       PROCEDURE DIVISION.
           OPEN INPUT TCAT-IN OUTPUT TCAT-KS
           MOVE 'N' TO WS-EOF MOVE 0 TO WS-COUNT
           PERFORM UNTIL WS-EOF = 'Y'
              MOVE SPACES TO TI-REC
              READ TCAT-IN AT END MOVE 'Y' TO WS-EOF
              NOT AT END
                 WRITE TK-REC FROM TI-REC
                 PERFORM CHECK-WRITE
              END-READ
           END-PERFORM
           CLOSE TCAT-IN TCAT-KS
           DISPLAY 'PARLOAD TCATBALF ' WS-COUNT

           OPEN INPUT XREF-IN OUTPUT XREF-KS
           MOVE 'N' TO WS-EOF MOVE 0 TO WS-COUNT
           PERFORM UNTIL WS-EOF = 'Y'
              MOVE SPACES TO XI-REC
              READ XREF-IN AT END MOVE 'Y' TO WS-EOF
              NOT AT END
                 WRITE XK-REC FROM XI-REC
                 PERFORM CHECK-WRITE
              END-READ
           END-PERFORM
           CLOSE XREF-IN XREF-KS
           DISPLAY 'PARLOAD XREFFILE ' WS-COUNT

           OPEN INPUT ACCT-IN OUTPUT ACCT-KS
           MOVE 'N' TO WS-EOF MOVE 0 TO WS-COUNT
           PERFORM UNTIL WS-EOF = 'Y'
              MOVE SPACES TO AI-REC
              READ ACCT-IN AT END MOVE 'Y' TO WS-EOF
              NOT AT END
                 WRITE AK-REC FROM AI-REC
                 PERFORM CHECK-WRITE
              END-READ
           END-PERFORM
           CLOSE ACCT-IN ACCT-KS
           DISPLAY 'PARLOAD ACCTFILE ' WS-COUNT

           OPEN INPUT DISC-IN OUTPUT DISC-KS
           MOVE 'N' TO WS-EOF MOVE 0 TO WS-COUNT
           PERFORM UNTIL WS-EOF = 'Y'
              MOVE SPACES TO DI-REC
              READ DISC-IN AT END MOVE 'Y' TO WS-EOF
              NOT AT END
                 WRITE DK-REC FROM DI-REC
                 PERFORM CHECK-WRITE
              END-READ
           END-PERFORM
           CLOSE DISC-IN DISC-KS
           DISPLAY 'PARLOAD DISCGRP ' WS-COUNT
           STOP RUN.

       CHECK-WRITE.
           IF WS-STAT NOT = '00'
              DISPLAY 'PARLOAD WRITE FAILED, STATUS ' WS-STAT
              MOVE 8 TO RETURN-CODE
              STOP RUN
           END-IF
           ADD 1 TO WS-COUNT.
