-- Ledger reconciliation for an XFRDAILY posting window (cut-over rollback).
--
-- Proves that the ledger rows posted in the window fully explain the
-- difference between the pre-window account master and the ACCTDATA.XFER
-- generation that is about to be (or was) restored, and that the
-- XFER.FEES generation matches the ledger row for row.
--
-- Inputs (psql variables / temp tables, loaded by tools/cutover/rollback_dryrun.py
-- or by the operator from IDCAMS PRINT / DSNTIAUL unloads):
--   :since_ts        ledger rows with POSTED_TS > since_ts form the window
--   ACCT_BASE        account master before the window   (ACCTDATA.PS backup)
--   ACCT_GEN         ACCTDATA.XFER(0) generation         (rollback source)
--   FEES_GEN         XFER.FEES(0) generation
--
-- Posting rule mirrored from cobol/XFERFEE.cbl (2100-PROCESS-TRANSFER):
--   source: BAL -= TRAN_AMT + FEE_AMT ; CYC_DEBIT  += TRAN_AMT + FEE_AMT
--   target: BAL += TRAN_AMT           ; CYC_CREDIT += TRAN_AMT
--
-- Zero rows from xfer_recon_breaks = clean. Any row is a break: do not restore.

CREATE TEMP VIEW xfer_recon_window AS
SELECT *
  FROM XFER_FEE_LEDGER
 WHERE POSTED_TS > CAST(:'since_ts' AS TIMESTAMP);

CREATE TEMP VIEW xfer_recon_expected AS
SELECT ACCT_ID,
       SUM(D_BAL)    AS D_BAL,
       SUM(D_CREDIT) AS D_CREDIT,
       SUM(D_DEBIT)  AS D_DEBIT
  FROM (SELECT SRC_ACCT_ID AS ACCT_ID,
               -(TRAN_AMT + FEE_AMT) AS D_BAL,
               0::NUMERIC AS D_CREDIT,
               TRAN_AMT + FEE_AMT AS D_DEBIT
          FROM xfer_recon_window
        UNION ALL
        SELECT TGT_ACCT_ID, TRAN_AMT, TRAN_AMT, 0
          FROM xfer_recon_window) MOVES
 GROUP BY ACCT_ID;

CREATE TEMP VIEW xfer_recon_breaks AS
SELECT 'ACCOUNT' AS CHECK_NAME,
       LPAD(COALESCE(G.ACCT_ID, B.ACCT_ID)::TEXT, 11, '0') AS ITEM,
       CASE
         WHEN G.ACCT_ID IS NULL THEN 'missing from ACCTDATA.XFER(0)'
         WHEN B.ACCT_ID IS NULL THEN 'missing from pre-window master'
         ELSE 'bal ' || (G.CURR_BAL - B.CURR_BAL) || ' vs ledger ' || COALESCE(E.D_BAL, 0)
              || '; cyc_credit ' || (G.CYC_CREDIT - B.CYC_CREDIT) || ' vs ' || COALESCE(E.D_CREDIT, 0)
              || '; cyc_debit ' || (G.CYC_DEBIT - B.CYC_DEBIT) || ' vs ' || COALESCE(E.D_DEBIT, 0)
       END AS DETAIL
  FROM ACCT_GEN G
  FULL OUTER JOIN ACCT_BASE B ON B.ACCT_ID = G.ACCT_ID
  LEFT JOIN xfer_recon_expected E ON E.ACCT_ID = COALESCE(G.ACCT_ID, B.ACCT_ID)
 WHERE G.ACCT_ID IS NULL
    OR B.ACCT_ID IS NULL
    OR G.CURR_BAL   - B.CURR_BAL   <> COALESCE(E.D_BAL, 0)
    OR G.CYC_CREDIT - B.CYC_CREDIT <> COALESCE(E.D_CREDIT, 0)
    OR G.CYC_DEBIT  - B.CYC_DEBIT  <> COALESCE(E.D_DEBIT, 0)
UNION ALL
SELECT 'LEDGER_ACCT',
       LPAD(E.ACCT_ID::TEXT, 11, '0'),
       'ledger posts to an account absent from both masters'
  FROM xfer_recon_expected E
 WHERE NOT EXISTS (SELECT 1 FROM ACCT_GEN G WHERE G.ACCT_ID = E.ACCT_ID)
   AND NOT EXISTS (SELECT 1 FROM ACCT_BASE B WHERE B.ACCT_ID = E.ACCT_ID)
UNION ALL
SELECT 'FEES_VS_LEDGER',
       COALESCE(F.TRAN_ID, L.TRAN_ID),
       CASE
         WHEN L.TRAN_ID IS NULL THEN 'in XFER.FEES(0), not in ledger window'
         WHEN F.TRAN_ID IS NULL THEN 'in ledger window, not in XFER.FEES(0)'
         ELSE 'field mismatch'
       END
  FROM FEES_GEN F
  FULL OUTER JOIN xfer_recon_window L ON L.TRAN_ID = F.TRAN_ID
 WHERE F.TRAN_ID IS NULL
    OR L.TRAN_ID IS NULL
    OR F.TRAN_DT <> L.TRAN_DT
    OR F.SRC_ACCT_ID <> L.SRC_ACCT_ID
    OR F.TGT_ACCT_ID <> L.TGT_ACCT_ID
    OR F.BOOK_ID <> L.BOOK_ID
    OR F.TRAN_AMT <> L.TRAN_AMT
    OR F.FEE_AMT <> L.FEE_AMT
    OR F.CAP_APPLIED <> L.CAP_APPLIED;

\echo '-- window totals by book (XFER_FEE_LEDGER, POSTED_TS > since_ts)'
SELECT BOOK_ID, COUNT(*) AS TRANSFERS, SUM(TRAN_AMT) AS TRAN_AMT,
       SUM(FEE_AMT) AS FEE_AMT,
       SUM(CASE WHEN CAP_APPLIED = 'Y' THEN 1 ELSE 0 END) AS CAPPED
  FROM xfer_recon_window
 GROUP BY BOOK_ID
 ORDER BY BOOK_ID;

\echo '-- breaks (empty = clean)'
SELECT * FROM xfer_recon_breaks ORDER BY CHECK_NAME, ITEM;

SELECT 'RECON_BREAKS=' || COUNT(*) AS RESULT FROM xfer_recon_breaks;
