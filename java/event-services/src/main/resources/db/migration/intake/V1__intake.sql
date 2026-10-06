-- Reference copies loaded per business day (CARDXREF.PS / ACCTDATA.PS), file order kept in seq.
CREATE TABLE card_xref (
    seq      INTEGER     PRIMARY KEY,
    card_num VARCHAR(16) NOT NULL,
    cust_id  BIGINT      NOT NULL,
    acct_id  BIGINT      NOT NULL
);

CREATE TABLE account_ref (
    seq     INTEGER PRIMARY KEY,
    payload TEXT    NOT NULL
);

-- DALYTRAN as received, the XFER.EXTRACT generation and the BR-05 rejects, per run.
CREATE TABLE daily_transaction (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE extract_record (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE reject (
    run_id      VARCHAR(100) NOT NULL,
    seq         BIGINT       NOT NULL,
    payload     TEXT         NOT NULL,
    sysout_line TEXT         NOT NULL,
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

