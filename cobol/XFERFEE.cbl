       IDENTIFICATION DIVISION.
       PROGRAM-ID. XFERFEE.
      *****************************************************************
      * Program : XFERFEE
      * Application : CardDemo
      * Type : BATCH COBOL Program
      * Function : Post transfer fees and update account master
      *****************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT XFEREXTR ASSIGN TO "XFEREXTR"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-XFER-STATUS.
           SELECT ACCTFILE ASSIGN TO "ACCTFILE"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-ACCT-STATUS.
           SELECT ACCTOUT ASSIGN TO "ACCTOUT"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-OUT-STATUS.
           SELECT XFERFEE ASSIGN TO "XFERFEE"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-FEE-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  XFEREXTR RECORD CONTAINS 120 CHARACTERS.
           COPY CVXFR01Y.
       FD  ACCTFILE RECORD CONTAINS 300 CHARACTERS.
           COPY CVACT01Y.
       FD  ACCTOUT RECORD CONTAINS 300 CHARACTERS.
           COPY CVACT01Y.
       FD  XFERFEE RECORD CONTAINS 100 CHARACTERS.
           COPY CVXFR02Y.
       WORKING-STORAGE SECTION.
           EXEC SQL
               INCLUDE SQLCA
           END-EXEC.
       01  WS-STATUS-CODES.
           05  WS-XFER-STATUS             PIC XX.
           05  WS-ACCT-STATUS             PIC XX.
           05  WS-OUT-STATUS              PIC XX.
           05  WS-FEE-STATUS              PIC XX.
       01  WS-FLAGS.
           05  WS-XFER-EOF                PIC X VALUE "N".
           05  WS-FOUND                    PIC X VALUE "N".
       01  WS-COUNTS.
           05  WS-ACCT-COUNT               PIC 9(04) VALUE 0.
           05  WS-TRANSFER-COUNT           PIC 9(09) VALUE 0.
           05  WS-FEE-TOTAL                PIC S9(09)V99 COMP-3 VALUE 0.
       01  WS-SUBSCRIPTS.
           05  WS-ACCT-SUB                 PIC 9(04).
           05  WS-SRC-SUB                  PIC 9(04).
           05  WS-TGT-SUB                  PIC 9(04).
       01  WS-ACCOUNT-TABLE.
           05  WS-ACCOUNT-ROW OCCURS 500 TIMES.
               10  WS-A-ID                 PIC 9(11).
               10  WS-A-ACTIVE             PIC X.
               10  WS-A-BAL                PIC S9(10)V99.
               10  WS-A-CREDIT-LIMIT       PIC S9(10)V99.
               10  WS-A-CASH-LIMIT         PIC S9(10)V99.
               10  WS-A-OPEN-DATE          PIC X(10).
               10  WS-A-EXP-DATE           PIC X(10).
               10  WS-A-REISSUE-DATE       PIC X(10).
               10  WS-A-CYC-CREDIT         PIC S9(10)V99.
               10  WS-A-CYC-DEBIT          PIC S9(10)V99.
               10  WS-A-ZIP                PIC X(10).
               10  WS-A-BOOK               PIC X(10).
               10  WS-A-FILLER             PIC X(178).
       01  WS-DB-NAME                     PIC X(64).
       01  WS-DB-USER                     PIC X(64).
       01  WS-DB-PASS                     PIC X(128).
       01  WS-FEE-PCT                     PIC S9(1)V9(6) COMP-3.
       01  WS-FEE-CAP                     PIC S9(09)V99 COMP-3.
       01  WS-FEE-AMT                     PIC S9(09)V99 COMP-3.
       01  WS-RULE-EFF-DT                 PIC X(10).
       01  WS-LEGACY-CONTROL.
           COPY CVXFR09Y.
       PROCEDURE DIVISION.
       0000-MAIN.
           ACCEPT WS-DB-NAME FROM ENVIRONMENT "OCDB_NAME"
           ACCEPT WS-DB-USER FROM ENVIRONMENT "OCDB_USER"
           ACCEPT WS-DB-PASS FROM ENVIRONMENT "OCDB_PASS"
           OPEN INPUT ACCTFILE XFEREXTR
                OUTPUT ACCTOUT XFERFEE
           IF WS-ACCT-STATUS NOT = "00"
               PERFORM 9999-ABEND-PROGRAM
           END-IF
           PERFORM 1000-LOAD-MASTER
           EXEC SQL
               CONNECT TO :WS-DB-NAME USER :WS-DB-USER
                   USING :WS-DB-PASS
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY "XFERFEE: DATABASE CONNECT FAILED " SQLCODE
               PERFORM 9999-ABEND-PROGRAM
           END-IF
           PERFORM 2000-POST-TRANSFERS
           PERFORM 3000-WRITE-MASTER
           EXEC SQL
               COMMIT
           END-EXEC
           CLOSE ACCTFILE XFEREXTR ACCTOUT XFERFEE
           DISPLAY "XFERFEE: TRANSFERS POSTED " WS-TRANSFER-COUNT
           DISPLAY "XFERFEE: TOTAL FEES " WS-FEE-TOTAL
           MOVE 0 TO RETURN-CODE
           GOBACK.
       1000-LOAD-MASTER.
           PERFORM UNTIL WS-ACCT-STATUS = "10"
               READ ACCTFILE
                   AT END MOVE "10" TO WS-ACCT-STATUS
                   NOT AT END
                       IF WS-ACCT-COUNT < 500
                           ADD 1 TO WS-ACCT-COUNT
                           MOVE ACCT-ID TO WS-A-ID(WS-ACCT-COUNT)
                           MOVE ACCT-ACTIVE-STATUS
                             TO WS-A-ACTIVE(WS-ACCT-COUNT)
                           MOVE ACCT-CURR-BAL
                             TO WS-A-BAL(WS-ACCT-COUNT)
                           MOVE ACCT-CREDIT-LIMIT
                             TO WS-A-CREDIT-LIMIT(WS-ACCT-COUNT)
                           MOVE ACCT-CASH-CREDIT-LIMIT
                             TO WS-A-CASH-LIMIT(WS-ACCT-COUNT)
                           MOVE ACCT-OPEN-DATE
                             TO WS-A-OPEN-DATE(WS-ACCT-COUNT)
                           MOVE ACCT-EXPIRAION-DATE
                             TO WS-A-EXP-DATE(WS-ACCT-COUNT)
                           MOVE ACCT-REISSUE-DATE
                             TO WS-A-REISSUE-DATE(WS-ACCT-COUNT)
                           MOVE ACCT-CURR-CYC-CREDIT
                             TO WS-A-CYC-CREDIT(WS-ACCT-COUNT)
                           MOVE ACCT-CURR-CYC-DEBIT
                             TO WS-A-CYC-DEBIT(WS-ACCT-COUNT)
                           MOVE ACCT-ADDR-ZIP
                             TO WS-A-ZIP(WS-ACCT-COUNT)
                           MOVE ACCT-GROUP-ID
                             TO WS-A-BOOK(WS-ACCT-COUNT)
                       END-IF
               END-READ
           END-PERFORM.
       2000-POST-TRANSFERS.
           MOVE "N" TO WS-XFER-EOF
           PERFORM UNTIL WS-XFER-EOF = "Y"
               READ XFEREXTR
                   AT END MOVE "Y" TO WS-XFER-EOF
                   NOT AT END PERFORM 2100-POST-ONE
               END-READ
           END-PERFORM.
       2100-POST-ONE.
           MOVE 0 TO WS-FEE-AMT
           MOVE "N" TO XFE-CAP-APPLIED
           MOVE SPACES TO WS-RULE-EFF-DT
           EXEC SQL
               SELECT FEE_PCT, FEE_CAP, EFF_DT
                 INTO :WS-FEE-PCT, :WS-FEE-CAP, :WS-RULE-EFF-DT
                 FROM CTL_XFER_PARM
                WHERE BOOK_ID = :XFR-BOOK-ID
                  AND EFF_DT <= CAST(:XFR-TRAN-DT AS DATE)
                  AND EXP_DT > CAST(:XFR-TRAN-DT AS DATE)
           END-EXEC
           IF SQLCODE = 100
               DISPLAY "XFERFEE: NO FEE RULE FOR BOOK " XFR-BOOK-ID
               MOVE 8 TO RETURN-CODE
               PERFORM 9999-ABEND-PROGRAM
           ELSE
               IF SQLCODE NOT = 0
                   DISPLAY "XFERFEE: RULE LOOKUP FAILED " SQLCODE
                   PERFORM 9999-ABEND-PROGRAM
               END-IF
           END-IF
           IF XFR-TRAN-AMT NOT = 0
               COMPUTE WS-FEE-AMT ROUNDED =
                   XFR-TRAN-AMT * WS-FEE-PCT
               IF WS-FEE-AMT > WS-FEE-CAP
                   MOVE WS-FEE-CAP TO WS-FEE-AMT
                   MOVE "Y" TO XFE-CAP-APPLIED
               END-IF
           END-IF
           PERFORM 2200-FIND-ACCOUNTS
           IF WS-FOUND = "N"
               DISPLAY "XFERFEE: ACCOUNT NOT FOUND "
                   XFR-SRC-ACCT-ID " / " XFR-TGT-ACCT-ID
               PERFORM 9999-ABEND-PROGRAM
           END-IF
           SUBTRACT XFR-TRAN-AMT FROM WS-A-BAL(WS-SRC-SUB)
           SUBTRACT WS-FEE-AMT FROM WS-A-BAL(WS-SRC-SUB)
           ADD XFR-TRAN-AMT TO WS-A-BAL(WS-TGT-SUB)
           ADD XFR-TRAN-AMT TO WS-A-CYC-CREDIT(WS-TGT-SUB)
           ADD XFR-TRAN-AMT WS-FEE-AMT TO WS-A-CYC-DEBIT(WS-SRC-SUB)
           MOVE XFR-TRAN-ID TO XFE-TRAN-ID
           MOVE XFR-TRAN-DT TO XFE-TRAN-DT
           MOVE XFR-SRC-ACCT-ID TO XFE-SRC-ACCT-ID
           MOVE XFR-TGT-ACCT-ID TO XFE-TGT-ACCT-ID
           MOVE XFR-BOOK-ID TO XFE-BOOK-ID
           MOVE XFR-TRAN-AMT TO XFE-TRAN-AMT
           MOVE WS-FEE-PCT TO XFE-FEE-PCT
           MOVE WS-FEE-AMT TO XFE-FEE-AMT
           MOVE WS-RULE-EFF-DT TO XFE-RULE-EFF-DT
           WRITE XFER-FEE-RECORD
           IF WS-FEE-STATUS NOT = "00"
               PERFORM 9999-ABEND-PROGRAM
           END-IF
           EXEC SQL
               INSERT INTO XFER_FEE_LEDGER
                   (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID,
                    BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED)
               VALUES
                   (:XFE-TRAN-ID, CAST(:XFE-TRAN-DT AS DATE),
                    :XFE-SRC-ACCT-ID, :XFE-TGT-ACCT-ID,
                    :XFE-BOOK-ID, :XFE-TRAN-AMT, :XFE-FEE-AMT,
                    :XFE-CAP-APPLIED)
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY "XFERFEE: LEDGER INSERT FAILED " SQLCODE
               PERFORM 9999-ABEND-PROGRAM
           END-IF
           ADD 1 TO WS-TRANSFER-COUNT
           ADD WS-FEE-AMT TO WS-FEE-TOTAL.
       2200-FIND-ACCOUNTS.
           MOVE "N" TO WS-FOUND
           MOVE 0 TO WS-SRC-SUB WS-TGT-SUB
           PERFORM VARYING WS-ACCT-SUB FROM 1 BY 1
               UNTIL WS-ACCT-SUB > WS-ACCT-COUNT
               IF WS-A-ID(WS-ACCT-SUB) = XFR-SRC-ACCT-ID
                   MOVE WS-ACCT-SUB TO WS-SRC-SUB
               END-IF
               IF WS-A-ID(WS-ACCT-SUB) = XFR-TGT-ACCT-ID
                   MOVE WS-ACCT-SUB TO WS-TGT-SUB
               END-IF
           END-PERFORM
           IF WS-SRC-SUB > 0 AND WS-TGT-SUB > 0
               MOVE "Y" TO WS-FOUND
           END-IF.
       3000-WRITE-MASTER.
           PERFORM VARYING WS-ACCT-SUB FROM 1 BY 1
               UNTIL WS-ACCT-SUB > WS-ACCT-COUNT
               MOVE WS-A-ID(WS-ACCT-SUB) TO ACCT-ID
               MOVE WS-A-ACTIVE(WS-ACCT-SUB) TO ACCT-ACTIVE-STATUS
               MOVE WS-A-BAL(WS-ACCT-SUB) TO ACCT-CURR-BAL
               MOVE WS-A-CREDIT-LIMIT(WS-ACCT-SUB)
                 TO ACCT-CREDIT-LIMIT
               MOVE WS-A-CASH-LIMIT(WS-ACCT-SUB)
                 TO ACCT-CASH-CREDIT-LIMIT
               MOVE WS-A-OPEN-DATE(WS-ACCT-SUB) TO ACCT-OPEN-DATE
               MOVE WS-A-EXP-DATE(WS-ACCT-SUB)
                 TO ACCT-EXPIRAION-DATE
               MOVE WS-A-REISSUE-DATE(WS-ACCT-SUB)
                 TO ACCT-REISSUE-DATE
               MOVE WS-A-CYC-CREDIT(WS-ACCT-SUB)
                 TO ACCT-CURR-CYC-CREDIT
               MOVE WS-A-CYC-DEBIT(WS-ACCT-SUB)
                 TO ACCT-CURR-CYC-DEBIT
               MOVE WS-A-ZIP(WS-ACCT-SUB) TO ACCT-ADDR-ZIP
               MOVE WS-A-BOOK(WS-ACCT-SUB) TO ACCT-GROUP-ID
               WRITE ACCOUNT-RECORD
               IF WS-OUT-STATUS NOT = "00"
                   PERFORM 9999-ABEND-PROGRAM
               END-IF
           END-PERFORM.
      * 03/17/87 JRW  FEE IS A FLAT $2.50 PER WIRE, NO CAP. DO NOT
      * CHANGE WITHOUT TREASURY SIGN-OFF
      * 11/02/93 CHANGED TO 1% PER NYCE
       9999-ABEND-PROGRAM.
           DISPLAY "XFERFEE: 9999-ABEND-PROGRAM"
           MOVE 8 TO RETURN-CODE
           STOP RUN.
