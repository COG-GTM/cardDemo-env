       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBXFR03C.
      *****************************************************************
      * Program : CBXFR03C
      * Application : CardDemo
      * Type : BATCH COBOL Program
      * Function : Reconcile transfer fee postings by book
      *****************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT XFERFEE ASSIGN TO "XFERFEE"
               ORGANIZATION IS SEQUENTIAL.
           SELECT XFERRPT ASSIGN TO "XFERRPT"
               ORGANIZATION IS LINE SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  XFERFEE RECORD CONTAINS 100 CHARACTERS.
           COPY CVXFR02Y.
       FD  XFERRPT RECORD CONTAINS 133 CHARACTERS.
       01  XFERRPT-REC PIC X(133).
       WORKING-STORAGE SECTION.
       01  WS-EOF                       PIC X VALUE "N".
       01  WS-COUNT                     PIC 9(09) VALUE 0.
       01  WS-GRAND-AMT                 PIC S9(09)V99 COMP-3 VALUE 0.
       01  WS-GRAND-FEE                 PIC S9(09)V99 COMP-3 VALUE 0.
       01  WS-BOOK-AMT                  PIC S9(09)V99 COMP-3 VALUE 0.
       01  WS-BOOK-FEE                  PIC S9(09)V99 COMP-3 VALUE 0.
       01  WS-LAST-BOOK                 PIC X(10) VALUE SPACES.
       01  WS-LINE                      PIC X(133).
       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT XFERFEE OUTPUT XFERRPT
           MOVE SPACES TO WS-LINE
           STRING " TRANSFER FEE RECONCILIATION"
               DELIMITED BY SIZE INTO WS-LINE
           WRITE XFERRPT-REC FROM WS-LINE
           MOVE SPACES TO WS-LINE
           STRING " TRANSACTION       DATE       BOOK       "
               "AMOUNT          FEE"
               DELIMITED BY SIZE INTO WS-LINE
           WRITE XFERRPT-REC FROM WS-LINE
           PERFORM UNTIL WS-EOF = "Y"
               READ XFERFEE
                   AT END MOVE "Y" TO WS-EOF
                   NOT AT END PERFORM 1000-RECORD
               END-READ
           END-PERFORM
           IF WS-COUNT > 0
               PERFORM 2000-SUBTOTAL
               MOVE SPACES TO WS-LINE
               STRING " GRAND TOTAL COUNT " WS-COUNT
                   " AMOUNT " WS-GRAND-AMT " FEE " WS-GRAND-FEE
                   DELIMITED BY SIZE INTO WS-LINE
               WRITE XFERRPT-REC FROM WS-LINE
               DISPLAY "CBXFR03C: GRAND TOTAL FEE " WS-GRAND-FEE
               MOVE 0 TO RETURN-CODE
           ELSE
               DISPLAY "CBXFR03C: NO FEE RECORDS"
               MOVE 4 TO RETURN-CODE
           END-IF
           CLOSE XFERFEE XFERRPT
           GOBACK.
       1000-RECORD.
           IF WS-LAST-BOOK NOT = SPACES
               AND WS-LAST-BOOK NOT = XFE-BOOK-ID
               PERFORM 2000-SUBTOTAL
           END-IF
           IF WS-LAST-BOOK = SPACES
               MOVE XFE-BOOK-ID TO WS-LAST-BOOK
           END-IF
           ADD 1 TO WS-COUNT
           ADD XFE-TRAN-AMT TO WS-GRAND-AMT WS-BOOK-AMT
           ADD XFE-FEE-AMT TO WS-GRAND-FEE WS-BOOK-FEE
           MOVE SPACES TO WS-LINE
           STRING " " XFE-TRAN-ID " " XFE-TRAN-DT " "
               XFE-BOOK-ID " " XFE-TRAN-AMT " " XFE-FEE-AMT
               DELIMITED BY SIZE INTO WS-LINE
           WRITE XFERRPT-REC FROM WS-LINE.
       2000-SUBTOTAL.
           MOVE SPACES TO WS-LINE
           STRING " BOOK " WS-LAST-BOOK " SUBTOTAL AMOUNT "
               WS-BOOK-AMT " FEE " WS-BOOK-FEE
               DELIMITED BY SIZE INTO WS-LINE
           WRITE XFERRPT-REC FROM WS-LINE
           MOVE 0 TO WS-BOOK-AMT WS-BOOK-FEE
           MOVE XFE-BOOK-ID TO WS-LAST-BOOK.
