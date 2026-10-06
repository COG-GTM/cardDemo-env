-- CTL_XFER_PARM migrated as-is (db2/ddl/CTL_XFER_PARM.sql); BOOK_ID stored trimmed.
CREATE TABLE ctl_xfer_parm (
    book_id VARCHAR(10)   NOT NULL,
    fee_pct NUMERIC(7, 6) NOT NULL,
    fee_cap NUMERIC(11, 2) NOT NULL,
    eff_dt  DATE          NOT NULL,
    exp_dt  DATE          NOT NULL,
    PRIMARY KEY (book_id, eff_dt)
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

