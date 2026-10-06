-- Account master (ACCTDATA) in file order; payload is the contracts Account JSON.
CREATE TABLE account (
    seq     INTEGER PRIMARY KEY,
    acct_id BIGINT  NOT NULL,
    payload TEXT    NOT NULL
);

-- XFER_FEE_LEDGER as in db2/ddl/XFER_FEE_LEDGER.sql: TRAN_ID is the identity (BR-14).
CREATE TABLE xfer_fee_ledger (
    tran_id     VARCHAR(16)    PRIMARY KEY,
    tran_dt     DATE           NOT NULL,
    src_acct_id BIGINT         NOT NULL,
    tgt_acct_id BIGINT         NOT NULL,
    book_id     VARCHAR(10)    NOT NULL,
    tran_amt    NUMERIC(11, 2) NOT NULL,
    fee_amt     NUMERIC(11, 2) NOT NULL,
    cap_applied CHAR(1)        NOT NULL
);

-- batch-atomic: transfers held until STEP010 completes, then posted in one transaction (D1).
CREATE TABLE pending_transfer (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

-- XFER.FEES generation per run, in posting order.
CREATE TABLE fee_record (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

-- per-transfer: transfers sent to the DLQ (D2/D3).
CREATE TABLE reject (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE step_report (
    run_id      VARCHAR(100) NOT NULL,
    step        VARCHAR(8)   NOT NULL,
    return_code INTEGER      NOT NULL,
    sysout      TEXT         NOT NULL,
    PRIMARY KEY (run_id, step)
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    msg_key      VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE inbox (
    message_id  VARCHAR(64) PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

