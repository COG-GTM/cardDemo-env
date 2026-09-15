       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBXFR01C.
      *****************************************************************
      * Program : CBXFR01C
      * Application : CardDemo
      * Type : BATCH COBOL Program
      * Function : Extract transfer transactions for fee posting
      *****************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DALYTRAN ASSIGN TO "DALYTRAN"
               ORGANIZATION IS SEQUENTIAL.
           SELECT XREFFILE ASSIGN TO "XREFFILE"
               ORGANIZATION IS SEQUENTIAL.
           SELECT ACCTFILE ASSIGN TO "ACCTFILE"
               ORGANIZATION IS SEQUENTIAL.
           SELECT XFEREXTR ASSIGN TO "XFEREXTR"
               ORGANIZATION IS SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  DALYTRAN RECORD CONTAINS 350 CHARACTERS.
           COPY CVTRA05Y.
       FD  XREFFILE RECORD CONTAINS 50 CHARACTERS.
           COPY CVACT03Y.
       FD  ACCTFILE RECORD CONTAINS 300 CHARACTERS.
           COPY CVACT01Y.
       FD  XFEREXTR RECORD CONTAINS 120 CHARACTERS.
           COPY CVXFR01Y.
       WORKING-STORAGE SECTION.
       01  WS-FLAGS.
           05  WS-EOF                    PIC X VALUE "N".
           05  WS-XREF-EOF               PIC X VALUE "N".
           05  WS-ACCT-EOF               PIC X VALUE "N".
       01  WS-COUNTS.
           05  WS-READ-COUNT              PIC 9(09) VALUE 0.
           05  WS-SELECT-COUNT            PIC 9(09) VALUE 0.
           05  WS-UNMATCHED-COUNT         PIC 9(09) VALUE 0.
       01  WS-XREF-TABLE.
           05  WS-XREF-COUNT              PIC 9(04) VALUE 0.
           05  WS-XREF-ROW OCCURS 500 TIMES.
               10  WS-XREF-CARD           PIC X(16).
               10  WS-XREF-ACCT           PIC 9(11).
       01  WS-ACCT-TABLE.
           05  WS-ACCT-COUNT              PIC 9(04) VALUE 0.
           05  WS-ACCT-ROW OCCURS 500 TIMES.
               10  WS-ACCT-ID             PIC 9(11).
               10  WS-ACCT-BOOK           PIC X(10).
       01  WS-SUBSCRIPTS.
           05  WS-XREF-SUB                PIC 9(04).
           05  WS-ACCT-SUB                PIC 9(04).
       01  WS-FOUND                       PIC X VALUE "N".
       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT XREFFILE ACCTFILE DALYTRAN
                OUTPUT XFEREXTR
           PERFORM 1000-LOAD-XREF
           PERFORM 1100-LOAD-ACCOUNTS
           PERFORM 2000-EXTRACT
           CLOSE XREFFILE ACCTFILE DALYTRAN XFEREXTR
           DISPLAY "CBXFR01C: RECORDS READ " WS-READ-COUNT
           DISPLAY "CBXFR01C: TRANSFERS SELECTED " WS-SELECT-COUNT
           DISPLAY "CBXFR01C: UNMATCHED CARDS " WS-UNMATCHED-COUNT
           IF WS-UNMATCHED-COUNT > 0
               MOVE 4 TO RETURN-CODE
           ELSE
               MOVE 0 TO RETURN-CODE
           END-IF
           GOBACK.
       1000-LOAD-XREF.
           PERFORM UNTIL WS-XREF-EOF = "Y"
               READ XREFFILE
                   AT END MOVE "Y" TO WS-XREF-EOF
                   NOT AT END
                       IF WS-XREF-COUNT < 500
                           ADD 1 TO WS-XREF-COUNT
                           MOVE XREF-CARD-NUM
                             TO WS-XREF-CARD(WS-XREF-COUNT)
                           MOVE XREF-ACCT-ID
                             TO WS-XREF-ACCT(WS-XREF-COUNT)
                       END-IF
               END-READ
           END-PERFORM.
       1100-LOAD-ACCOUNTS.
           PERFORM UNTIL WS-ACCT-EOF = "Y"
               READ ACCTFILE
                   AT END MOVE "Y" TO WS-ACCT-EOF
                   NOT AT END
                       IF WS-ACCT-COUNT < 500
                           ADD 1 TO WS-ACCT-COUNT
                           MOVE ACCT-ID
                             TO WS-ACCT-ID(WS-ACCT-COUNT)
                           MOVE ACCT-GROUP-ID
                             TO WS-ACCT-BOOK(WS-ACCT-COUNT)
                       END-IF
               END-READ
           END-PERFORM.
       2000-EXTRACT.
           MOVE "N" TO WS-EOF
           PERFORM UNTIL WS-EOF = "Y"
               READ DALYTRAN
                   AT END MOVE "Y" TO WS-EOF
                   NOT AT END
                       ADD 1 TO WS-READ-COUNT
                       IF TRAN-TYPE-CD = "08"
                           PERFORM 2100-WRITE-TRANSFER
                       END-IF
               END-READ
           END-PERFORM.
       2100-WRITE-TRANSFER.
           MOVE "N" TO WS-FOUND
           PERFORM VARYING WS-XREF-SUB FROM 1 BY 1
               UNTIL WS-XREF-SUB > WS-XREF-COUNT OR WS-FOUND = "Y"
               IF TRAN-CARD-NUM = WS-XREF-CARD(WS-XREF-SUB)
                   MOVE WS-XREF-ACCT(WS-XREF-SUB) TO XFR-SRC-ACCT-ID
                   MOVE "Y" TO WS-FOUND
               END-IF
           END-PERFORM
           IF WS-FOUND = "N"
               DISPLAY "CBXFR01C: CARD NOT FOUND " TRAN-CARD-NUM
               ADD 1 TO WS-UNMATCHED-COUNT
           ELSE
               MOVE "N" TO WS-FOUND
               PERFORM VARYING WS-ACCT-SUB FROM 1 BY 1
                   UNTIL WS-ACCT-SUB > WS-ACCT-COUNT OR WS-FOUND = "Y"
                   IF XFR-SRC-ACCT-ID = WS-ACCT-ID(WS-ACCT-SUB)
                       MOVE WS-ACCT-BOOK(WS-ACCT-SUB) TO XFR-BOOK-ID
                       MOVE "Y" TO WS-FOUND
                   END-IF
               END-PERFORM
               IF WS-FOUND = "N"
                   DISPLAY "CBXFR01C: ACCOUNT NOT FOUND "
                       XFR-SRC-ACCT-ID
                   ADD 1 TO WS-UNMATCHED-COUNT
               ELSE
                   MOVE TRAN-ID TO XFR-TRAN-ID
                   MOVE TRAN-ORIG-TS(1:10) TO XFR-TRAN-DT
                   MOVE TRAN-AMT TO XFR-TRAN-AMT
                   MOVE TRAN-CARD-NUM TO XFR-CARD-NUM
                   MOVE TRAN-DESC(14:11) TO XFR-TGT-ACCT-ID
                   WRITE XFER-EXTRACT-RECORD
                   ADD 1 TO WS-SELECT-COUNT
               END-IF
           END-IF.
