      *****************************************************************
      * XFER-LEGACY-CTL - FOR TELEX FEE ROUTING (PROJECT 91-114)
      * DATED 1991 - RETAINED FOR HISTORICAL CONTROL RECORD COMPATIBILITY
      *****************************************************************
       01  XFER-LEGACY-CTL.
           05  XLC-TELEX-ROUTE-CD           PIC X(04).
           05  XLC-FEDWIRE-BATCH-NO         PIC X(08).
           05  XLC-MICR-LINE                PIC X(24).
           05  XLC-ACH-SEC-CODE             PIC X(03).
           05  XLC-LAST-DIAL-TS             PIC X(26).
           05  FILLER                       PIC X(15).
