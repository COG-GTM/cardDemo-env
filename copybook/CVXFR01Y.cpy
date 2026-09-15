      *****************************************************************
      * XFER-EXTRACT-RECORD (RECLN 120)
      *****************************************************************
       01  XFER-EXTRACT-RECORD.
           05  XFR-TRAN-ID                 PIC X(16).
           05  XFR-TRAN-DT                 PIC X(10).
           05  XFR-SRC-ACCT-ID             PIC 9(11).
           05  XFR-TGT-ACCT-ID             PIC 9(11).
           05  XFR-BOOK-ID                 PIC X(10).
           05  XFR-TRAN-AMT                PIC S9(09)V99.
           05  XFR-CARD-NUM                PIC X(16).
           05  FILLER                      PIC X(35).
