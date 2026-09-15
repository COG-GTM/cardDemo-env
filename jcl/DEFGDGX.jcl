//DEFGDGX JOB 'DEFINE XFER GDGS',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
//* TRANSFER FEE GENERATION DATA GROUPS
//******************************************************************
//* *****************************************************************
//* * DEFGDGX - DEFINE GENERATION DATA GROUPS FOR TRANSFER FILES   *
//* *****************************************************************
//STEP01   EXEC PGM=IDCAMS
//SYSPRINT DD SYSOUT=*
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
  DEFINE GENERATIONDATAGROUP -
    (NAME(AWS.M2.CARDDEMO.XFER.EXTRACT) LIMIT(5) SCRATCH)
  DEFINE GENERATIONDATAGROUP -
    (NAME(AWS.M2.CARDDEMO.ACCTDATA.XFER) LIMIT(5) SCRATCH)
  DEFINE GENERATIONDATAGROUP -
    (NAME(AWS.M2.CARDDEMO.XFER.FEES) LIMIT(5) SCRATCH)
  DEFINE GENERATIONDATAGROUP -
    (NAME(AWS.M2.CARDDEMO.XFER.RECON.RPT) LIMIT(5) SCRATCH)
/*
