-- Migrated from db2/ddl/CTL_XFER_PARM.sql (legacy table CTL_XFER_PARM).
-- Column types and the primary key are unchanged so that a replay of a
-- fixture's db2_before snapshot dumps back byte-for-byte.
CREATE TABLE fee_rule (
    book_id CHAR(10)       NOT NULL,
    fee_pct NUMERIC(7, 6)  NOT NULL,
    fee_cap NUMERIC(11, 2) NOT NULL,
    eff_dt  DATE           NOT NULL,
    exp_dt  DATE           NOT NULL,
    PRIMARY KEY (book_id, eff_dt)
);
