//XFREXTR JOB 'TRANSFER EXTRACT',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
//* EXTRACT TRANSFER TRANSACTIONS FROM THE DAILY TRANSACTION FILE
//******************************************************************
//* *****************************************************************
//* * XFREXTR - SELECT TYPE 08 TRANSFERS FOR FEE POSTING          *
//* *****************************************************************
//STEP010  EXEC PGM=CBXFR01C
//STEPLIB  DD DSN=AWS.M2.CARDDEMO.LOADLIB,DISP=SHR
//DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS,DISP=SHR
//XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.PS,DISP=SHR
//ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.PS,DISP=SHR
//XFEREXTR DD DSN=AWS.M2.CARDDEMO.XFER.EXTRACT(+1),
//            DISP=(NEW,CATLG,DELETE),UNIT=SYSDA,
//            SPACE=(CYL,(1,1),RLSE),
//            DCB=(RECFM=FB,LRECL=120,BLKSIZE=0)
//SYSPRINT DD SYSOUT=*
//SYSOUT   DD SYSOUT=*
