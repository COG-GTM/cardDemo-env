-- COG-1252 ledger reconciliation for the xferfee rollback / cut-over.
--
-- Proves that an ACCTDATA.XFER generation, the XFER.FEES file of the same run and the
-- XFER_FEE_LEDGER rows of that run agree, and that the next DALYTRAN does not re-post a
-- TRAN_ID that is already in the ledger. Every check returns one row:
--   check | expected | actual | ok
-- and the run is clean only if every row has ok = t.
--
-- psql -v ON_ERROR_STOP=1 -At -F'|' -v load=<load.sql> \
--      -v run_from=<YYYY-MM-DD> -v run_to=<YYYY-MM-DD> -f ops/cutover/ledger_recon.sql
--
-- run_from / run_to bound the transaction dates of the run being checked (the type-08
-- records of that run's DALYTRAN). They are required and independent of the fees file, so
-- ledger rows of a partial run are caught even when its XFER.FEES generation is empty.
--
-- psql does not interpolate variables inside \copy, so <load.sql> holds the four loads
-- (tools/cutover/rollback_dryrun.py writes it; operators can hand-write it):
--   \copy rb_acct_before FROM '<csv>' CSV HEADER   acct_id,curr_bal,cyc_credit,cyc_debit
--                                                  master the run started from
--   \copy rb_acct_after FROM '<csv>' CSV HEADER    same columns, ACCTDATA.XFER generation
--                                                  that run produced
--   \copy rb_fees FROM '<csv>' CSV HEADER          tran_id,tran_dt,src_acct_id,tgt_acct_id,
--                                                  book_id,tran_amt,fee_amt,cap_applied
--                                                  (XFER.FEES generation of the same run)
--   \copy rb_incoming FROM '<csv>' CSV HEADER      tran_id (type-08 TRAN_IDs of the next
--                                                  DALYTRAN; header-only = none)
\set ON_ERROR_STOP on

CREATE TEMP TABLE rb_acct_before (
    acct_id    NUMERIC(11) PRIMARY KEY,
    curr_bal   NUMERIC(12, 2) NOT NULL,
    cyc_credit NUMERIC(12, 2) NOT NULL,
    cyc_debit  NUMERIC(12, 2) NOT NULL
);
CREATE TEMP TABLE rb_acct_after (LIKE rb_acct_before INCLUDING ALL);
CREATE TEMP TABLE rb_fees (
    tran_id     CHAR(16) PRIMARY KEY,
    tran_dt     DATE NOT NULL,
    src_acct_id NUMERIC(11) NOT NULL,
    tgt_acct_id NUMERIC(11) NOT NULL,
    book_id     CHAR(10) NOT NULL,
    tran_amt    NUMERIC(11, 2) NOT NULL,
    fee_amt     NUMERIC(11, 2) NOT NULL,
    cap_applied CHAR(1) NOT NULL
);
CREATE TEMP TABLE rb_incoming (tran_id CHAR(16) NOT NULL);

\i :load

WITH
run_ledger AS (
    SELECT l.*
    FROM XFER_FEE_LEDGER l
    JOIN rb_fees f ON f.tran_id = l.TRAN_ID
),
window_ledger AS (
    SELECT l.*
    FROM XFER_FEE_LEDGER l
    WHERE l.TRAN_DT BETWEEN :'run_from'::DATE AND :'run_to'::DATE
),
movement AS (
    SELECT acct_id, SUM(credit) AS credit, SUM(debit) AS debit
    FROM (
        SELECT SRC_ACCT_ID AS acct_id, 0::NUMERIC AS credit, TRAN_AMT + FEE_AMT AS debit
        FROM run_ledger
        UNION ALL
        SELECT TGT_ACCT_ID, TRAN_AMT, 0 FROM run_ledger
    ) legs
    GROUP BY acct_id
),
account_check AS (
    SELECT b.acct_id,
           a.curr_bal - b.curr_bal AS bal_delta,
           a.cyc_credit - b.cyc_credit AS credit_delta,
           a.cyc_debit - b.cyc_debit AS debit_delta,
           COALESCE(m.credit, 0) AS credit,
           COALESCE(m.debit, 0) AS debit
    FROM rb_acct_before b
    JOIN rb_acct_after a USING (acct_id)
    LEFT JOIN movement m USING (acct_id)
),
checks AS (
    SELECT 1 AS seq, 'fees_rows_in_ledger' AS name,
           (SELECT COUNT(*) FROM rb_fees)::TEXT AS expected,
           (SELECT COUNT(*) FROM run_ledger)::TEXT AS actual
    UNION ALL
    SELECT 2, 'fees_fields_match_ledger', '0',
           (SELECT COUNT(*) FROM rb_fees f JOIN XFER_FEE_LEDGER l ON l.TRAN_ID = f.tran_id
            WHERE (l.TRAN_DT, l.SRC_ACCT_ID, l.TGT_ACCT_ID, l.BOOK_ID, l.TRAN_AMT, l.FEE_AMT, l.CAP_APPLIED)
                  IS DISTINCT FROM
                  (f.tran_dt, f.src_acct_id, f.tgt_acct_id, f.book_id, f.tran_amt, f.fee_amt, f.cap_applied)
           )::TEXT
    UNION ALL
    SELECT 3, 'ledger_rows_not_in_fees_file', '0',
           (SELECT COUNT(*) FROM window_ledger w
            WHERE NOT EXISTS (SELECT 1 FROM rb_fees f WHERE f.tran_id = w.TRAN_ID))::TEXT
    UNION ALL
    SELECT 4, 'fee_total', COALESCE((SELECT SUM(fee_amt) FROM rb_fees), 0)::TEXT,
           COALESCE((SELECT SUM(FEE_AMT) FROM run_ledger), 0)::TEXT
    UNION ALL
    SELECT 5, 'account_set_unchanged', '0',
           (SELECT COUNT(*) FROM (
                SELECT acct_id FROM rb_acct_before
                EXCEPT SELECT acct_id FROM rb_acct_after) x)::TEXT
           || '/' ||
           (SELECT COUNT(*) FROM (
                SELECT acct_id FROM rb_acct_after
                EXCEPT SELECT acct_id FROM rb_acct_before) y)::TEXT
    UNION ALL
    SELECT 6, 'ledger_legs_unknown_account', '0',
           (SELECT COUNT(*) FROM movement m
            WHERE NOT EXISTS (SELECT 1 FROM rb_acct_before b WHERE b.acct_id = m.acct_id))::TEXT
    UNION ALL
    SELECT 7, 'accounts_not_explained_by_ledger', '0',
           (SELECT COUNT(*) FROM account_check
            WHERE bal_delta <> credit - debit
               OR credit_delta <> credit
               OR debit_delta <> debit)::TEXT
    UNION ALL
    SELECT 8, 'balance_total_delta_equals_minus_fees',
           (-COALESCE((SELECT SUM(fee_amt) FROM rb_fees), 0))::TEXT,
           ((SELECT COALESCE(SUM(curr_bal), 0) FROM rb_acct_after)
            - (SELECT COALESCE(SUM(curr_bal), 0) FROM rb_acct_before))::TEXT
    UNION ALL
    SELECT 9, 'ledger_duplicate_tran_ids', '0',
           (SELECT COUNT(*) FROM (
                SELECT TRAN_ID FROM XFER_FEE_LEDGER GROUP BY TRAN_ID HAVING COUNT(*) > 1) d)::TEXT
    UNION ALL
    SELECT 10, 'incoming_already_in_ledger', '0',
           (SELECT COUNT(*) FROM rb_incoming i
            JOIN XFER_FEE_LEDGER l ON l.TRAN_ID = i.tran_id)::TEXT
)
SELECT name, expected, actual,
       CASE WHEN name = 'account_set_unchanged' THEN actual = '0/0'
            ELSE expected::NUMERIC = actual::NUMERIC END AS ok
FROM checks
ORDER BY seq;
