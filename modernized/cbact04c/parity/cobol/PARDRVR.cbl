      ******************************************************************
      * PARDRVR - parity harness only (not part of CardDemo).
      * Plays the role of the JCL EXEC PARM=: passes the first command
      * line argument to CBACT04C as its EXTERNAL-PARMS linkage record.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. PARDRVR.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-ARG                 PIC X(100) VALUE SPACES.
       01  EXTERNAL-PARMS.
           05 PARM-LENGTH         PIC S9(04) COMP.
           05 PARM-DATA           PIC X(100).
       PROCEDURE DIVISION.
           ACCEPT WS-ARG FROM ARGUMENT-VALUE
           MOVE SPACES TO PARM-DATA
           MOVE WS-ARG TO PARM-DATA
           MOVE FUNCTION LENGTH(FUNCTION TRIM(WS-ARG TRAILING))
             TO PARM-LENGTH
           CALL 'CBACT04C' USING EXTERNAL-PARMS
           STOP RUN.
