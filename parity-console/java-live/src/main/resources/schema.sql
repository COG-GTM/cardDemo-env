CREATE SCHEMA IF NOT EXISTS java_engine;

CREATE TABLE IF NOT EXISTS java_engine.account (
    acct_id            BIGINT PRIMARY KEY,
    active_status      CHAR(1)        NOT NULL,
    curr_bal           NUMERIC(12, 2) NOT NULL,
    credit_limit       NUMERIC(12, 2) NOT NULL,
    cash_credit_limit  NUMERIC(12, 2) NOT NULL,
    open_date          VARCHAR(10)    NOT NULL,
    expiration_date    VARCHAR(10)    NOT NULL,
    reissue_date       VARCHAR(10)    NOT NULL,
    curr_cyc_credit    NUMERIC(12, 2) NOT NULL,
    curr_cyc_debit     NUMERIC(12, 2) NOT NULL,
    addr_zip           VARCHAR(10)    NOT NULL,
    group_id           VARCHAR(10)    NOT NULL
);

CREATE TABLE IF NOT EXISTS java_engine.card_xref (
    card_num  VARCHAR(16) PRIMARY KEY,
    cust_id   BIGINT      NOT NULL,
    acct_id   BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS java_engine.ctl_xfer_parm (
    book_id  VARCHAR(10)   NOT NULL,
    fee_pct  NUMERIC(7, 6) NOT NULL,
    fee_cap  NUMERIC(11, 2) NOT NULL,
    eff_dt   DATE          NOT NULL,
    exp_dt   DATE          NOT NULL,
    PRIMARY KEY (book_id, eff_dt)
);

CREATE TABLE IF NOT EXISTS java_engine.xfer_fee_ledger (
    tran_id      VARCHAR(16)    PRIMARY KEY,
    tran_dt      DATE           NOT NULL,
    src_acct_id  BIGINT         NOT NULL,
    tgt_acct_id  BIGINT         NOT NULL,
    book_id      VARCHAR(10)    NOT NULL,
    tran_amt     NUMERIC(11, 2) NOT NULL,
    fee_pct      NUMERIC(7, 6)  NOT NULL,
    fee_amt      NUMERIC(11, 2) NOT NULL,
    cap_applied  CHAR(1)        NOT NULL,
    rule_eff_dt  DATE           NOT NULL,
    posted_ts    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
