//XFRPURGE JOB 'PURGE OLD TRANSFER FEES',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
//* DEAD JOB - LAST RUN 03/2019 - REPLACED BY GDG LIMIT
//******************************************************************
//STEP01   EXEC PGM=IEFBR14
//OLD1     DD DSN=AWS.M2.CARDDEMO.XFER.FEES(-1),DISP=(MOD,DELETE)
//OLD2     DD DSN=AWS.M2.CARDDEMO.XFER.FEES(-2),DISP=(MOD,DELETE)
//SYSPRINT DD SYSOUT=*
