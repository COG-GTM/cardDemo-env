      *****************************************************************
      * XFER-FEE-RECORD (RECLN 100)
      *****************************************************************
       01  XFER-FEE-RECORD.
           05  XFE-TRAN-ID                 PIC X(16).
           05  XFE-TRAN-DT                 PIC X(10).
           05  XFE-SRC-ACCT-ID             PIC 9(11).
           05  XFE-TGT-ACCT-ID             PIC 9(11).
           05  XFE-BOOK-ID                 PIC X(10).
           05  XFE-TRAN-AMT                PIC S9(09)V99 COMP-3.
           05  XFE-FEE-PCT                 PIC S9(1)V9(6) COMP-3.
           05  XFE-FEE-AMT                 PIC S9(09)V99 COMP-3.
           05  XFE-CAP-APPLIED             PIC X(01).
           05  XFE-RULE-EFF-DT             PIC X(10).
           05  FILLER                      PIC X(15).
