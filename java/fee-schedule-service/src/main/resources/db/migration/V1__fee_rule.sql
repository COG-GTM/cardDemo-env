-- Migrated from db2/ddl/CTL_XFER_PARM.sql; column types kept so values round-trip unchanged.
CREATE TABLE fee_rule (
    book_id CHAR(10)      NOT NULL,
    fee_pct NUMERIC(7,6)  NOT NULL,
    fee_cap NUMERIC(11,2) NOT NULL,
    eff_dt  DATE          NOT NULL,
    exp_dt  DATE          NOT NULL,
    PRIMARY KEY (book_id, eff_dt)
);
