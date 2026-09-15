//XFRRECON JOB 'TRANSFER RECONCILIATION',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
//* RECONCILE THE TRANSFER FEE FILE BY BOOK
//******************************************************************
//* *****************************************************************
//* * XFRRECON - REPORT TRANSFER FEES BY ACCOUNT BOOK             *
//* *****************************************************************
//STEP030  EXEC PGM=CBXFR03C,COND=(4,LT)
//STEPLIB  DD DSN=AWS.M2.CARDDEMO.LOADLIB,DISP=SHR
//XFERFEE  DD DSN=AWS.M2.CARDDEMO.XFER.FEES(0),DISP=SHR
//XFERRPT  DD DSN=AWS.M2.CARDDEMO.XFER.RECON.RPT(+1),
//            DISP=(NEW,CATLG,DELETE),UNIT=SYSDA,
//            SPACE=(CYL,(1,1),RLSE),
//            DCB=(RECFM=FBA,LRECL=133,BLKSIZE=0)
//SYSPRINT DD SYSOUT=*
//SYSOUT   DD SYSOUT=*
