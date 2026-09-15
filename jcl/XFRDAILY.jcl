//XFRDAILY JOB 'DAILY TRANSFER FEES',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
//* DAILY TRANSFER FEE POSTING CHAIN
//******************************************************************
//* *****************************************************************
//* * XFRDAILY - RUN THE DAILY TRANSFER FEE POSTING PROCEDURE      *
//* *****************************************************************
//STEP01   EXEC PROC=XFERFEEP,HLQ=AWS.M2.CARDDEMO
//STEP010.DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS,DISP=SHR
//STEP010.XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.PS,DISP=SHR
//STEP010.ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.PS,DISP=SHR
//STEP020.ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.PS,DISP=SHR
