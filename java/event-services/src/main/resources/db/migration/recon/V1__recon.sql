CREATE TABLE posted_fee (
    run_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    payload TEXT         NOT NULL,
    PRIMARY KEY (run_id, seq)
);

-- XFER.RECON.RPT generation per run.
CREATE TABLE report_line (
    run_id  VARCHAR(100) NOT NULL,
    line_no INTEGER      NOT NULL,
    text    TEXT         NOT NULL,
    PRIMARY KEY (run_id, line_no)
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

