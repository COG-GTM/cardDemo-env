CREATE SCHEMA IF NOT EXISTS xfer_java;
CREATE TABLE IF NOT EXISTS xfer_java.service_ready (
    service TEXT PRIMARY KEY,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS xfer_java.card_xref (
    seq INTEGER PRIMARY KEY,
    card_num CHAR(16) NOT NULL,
    cust_id DECIMAL(9,0) NOT NULL,
    acct_id DECIMAL(11,0) NOT NULL
);
CREATE TABLE IF NOT EXISTS xfer_java.account (
    seq INTEGER PRIMARY KEY,
    acct_id DECIMAL(11,0) NOT NULL,
    active_status CHAR(1) NOT NULL,
    curr_bal DECIMAL(12,2) NOT NULL,
    credit_limit DECIMAL(12,2) NOT NULL,
    cash_credit_limit DECIMAL(12,2) NOT NULL,
    open_date CHAR(10) NOT NULL,
    expiration_date CHAR(10) NOT NULL,
    reissue_date CHAR(10) NOT NULL,
    curr_cyc_credit DECIMAL(12,2) NOT NULL,
    curr_cyc_debit DECIMAL(12,2) NOT NULL,
    addr_zip CHAR(10) NOT NULL,
    group_id CHAR(10) NOT NULL,
    raw BYTEA
);
ALTER TABLE xfer_java.account ADD COLUMN IF NOT EXISTS raw BYTEA;
CREATE TABLE IF NOT EXISTS xfer_java.run_step (
    run_id TEXT NOT NULL,
    step TEXT NOT NULL,
    rc INTEGER NOT NULL,
    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, step)
);
CREATE TABLE IF NOT EXISTS xfer_java.run_sysout (
    run_id TEXT NOT NULL,
    step TEXT NOT NULL,
    line_no BIGSERIAL,
    line TEXT NOT NULL,
    PRIMARY KEY (run_id, step, line_no)
);
CREATE TABLE IF NOT EXISTS xfer_java.intake_run (
    run_id TEXT PRIMARY KEY,
    read_count BIGINT NOT NULL DEFAULT 0,
    selected_count BIGINT NOT NULL DEFAULT 0,
    unmatched_count BIGINT NOT NULL DEFAULT 0,
    last_seq BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS xfer_java.extract_record (
    run_id TEXT NOT NULL,
    seq BIGINT NOT NULL,
    tran_id CHAR(16) NOT NULL,
    tran_dt CHAR(10) NOT NULL,
    src_acct_id DECIMAL(11,0) NOT NULL,
    tgt_acct_id DECIMAL(11,0) NOT NULL,
    book_id CHAR(10) NOT NULL,
    tran_amt DECIMAL(11,2) NOT NULL,
    card_num CHAR(16) NOT NULL,
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS xfer_java.posting_inbox (
    run_id TEXT NOT NULL,
    seq BIGINT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS xfer_java.fee_record (
    run_id TEXT NOT NULL,
    seq BIGINT NOT NULL,
    tran_id CHAR(16) NOT NULL,
    tran_dt CHAR(10) NOT NULL,
    src_acct_id DECIMAL(11,0) NOT NULL,
    tgt_acct_id DECIMAL(11,0) NOT NULL,
    book_id CHAR(10) NOT NULL,
    tran_amt DECIMAL(11,2) NOT NULL,
    fee_pct DECIMAL(7,6) NOT NULL,
    fee_amt DECIMAL(11,2) NOT NULL,
    cap_applied CHAR(1) NOT NULL,
    rule_eff_dt CHAR(10) NOT NULL,
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS xfer_java.outbox (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    msg_key TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS xfer_java.recon_entry (
    run_id TEXT NOT NULL,
    seq BIGINT NOT NULL,
    tran_id CHAR(16) NOT NULL,
    tran_dt CHAR(10) NOT NULL,
    book_id CHAR(10) NOT NULL,
    tran_amt DECIMAL(11,2) NOT NULL,
    fee_amt DECIMAL(11,2) NOT NULL,
    PRIMARY KEY (run_id, seq)
);
CREATE TABLE IF NOT EXISTS xfer_java.recon_report_line (
    run_id TEXT NOT NULL,
    line_no INTEGER NOT NULL,
    line TEXT NOT NULL,
    PRIMARY KEY (run_id, line_no)
);
