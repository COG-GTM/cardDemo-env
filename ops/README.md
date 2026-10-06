# Operations assets

| Path | What |
|---|---|
| `smf/job_activity.csv` | SMF-style job activity feeding the dead-code split (`make deadcode`) |
| `grafana/xferfee-chain-dashboard.json` | Grafana dashboard for `XFRDAILY` (Prometheus data source) |
| `grafana/xferfee-alert-rules.json` | Grafana alert-rule provisioning file (RC 8, RC 4, fee/recon mismatch) |
| `datadog/xferfee-chain-dashboard.json` | The same dashboard for Datadog (`POST /api/v1/dashboard`) |
| `datadog/xferfee-monitors.json` | The same alerts as Datadog monitors (one `POST /api/v1/monitor` each) |

## XFRDAILY signals (COG-1241)

The Java port (`java/observability`) turns what operators read in the JES log today into
Micrometer meters, `TransferRejected` counts, alerts and dead-letter entries.

| Today (SYSOUT / JES) | Micrometer meter (Prometheus name) | Dashboard panel |
|---|---|---|
| `CBXFR01C: RECORDS READ` | `carddemo.xferfee.extract.records.read` (`carddemo_xferfee_extract_records_read_total`) | RECORDS READ |
| `CBXFR01C: TRANSFERS SELECTED` | `carddemo.xferfee.extract.transfers.selected` | TRANSFERS SELECTED |
| `CBXFR01C: UNMATCHED CARDS` | `carddemo.xferfee.extract.unmatched.cards` | UNMATCHED CARDS |
| `XFERFEE: TRANSFERS POSTED` | `carddemo.xferfee.posting.transfers.posted` | TRANSFERS POSTED |
| `XFERFEE: TOTAL FEES` | `carddemo.xferfee.posting.fees.total` (base unit `currency`) | TOTAL FEES |
| `CBXFR03C: GRAND TOTAL FEE` | `carddemo.xferfee.recon.grand.total.fee` (base unit `currency`) | GRAND TOTAL FEE |
| `CARD NOT FOUND` / `ACCOUNT NOT FOUND` / `NO FEE RULE` ... | `carddemo.xferfee.transfers.rejected{step,program,reason}` | TransferRejected by reason |
| `COND CODE nnnn` | `carddemo.xferfee.step.return.code{step}` gauge, `carddemo.xferfee.step.runs{step,rc}` | step RC tiles |
| `$HASP395 ... MAXCC=nnnn` | `carddemo.xferfee.run.maxcc` gauge | XFRDAILY MAXCC |
| RC 4 | `carddemo.xferfee.step.warnings{step,program}` | RC 4 warnings, alert `xferfee-rc4` |
| RC 8+ | `carddemo.xferfee.step.alerts{step,program,rc}`, `carddemo.xferfee.dlq.entries{step,program}` | RC 8 alerts / DLQ, alert `xferfee-rc8` |

`reason` is the `contracts.RejectReason` name (`UNMATCHED_CARD`, `UNKNOWN_SOURCE_ACCOUNT`,
`NO_FEE_RULE`, `UNKNOWN_ACCOUNT`, `DUPLICATE_TRAN_ID`, `POSTING_ERROR`).

### Return-code runbook

* **RC 0**: nothing to do. `TOTAL FEES` and `GRAND TOTAL FEE` must be equal; the
  `xferfee-recon-mismatch` alert fires if they are not.
* **RC 4 (warning)**: the chain completed. STEP010 skipped transfers whose card or source
  account is missing (BR-05: see `TransferRejected` by reason), or STEP030 found no fee records
  (BR-19). Fix the reference data; the rejected transfers are not retried automatically.
* **RC 8 (alert)**: STEP020 abended (no effective fee rule, missing account, duplicate ledger
  key or SQL error). Nothing was posted (BR-15) and STEP030 did not run
  (`COND=(4,LT,STEP020)`). The dead-letter entry carries run date, step, reason, the rejected
  transfer and the step's SYSOUT. Fix the cause and rerun the whole job.

### Parity

`make parity-java` replays each fixture through `parity-replay`, which writes
`sysout/STEP0x0.txt` (SYSOUT text), `sysout/STEP0x0.json` (counters, RC, severity, rejects),
`rc.json` and `observability/{alerts.jsonl,dlq.jsonl,metrics.json}`.
`tools/parity/compare.py --scope signals` diffs SYSOUT and RC; `tools/parity/compare_counters.py`
diffs the JSON counters against the counter lines in `expected/sysout`.
`OpsDashboardsTest` fails if a dashboard or alert queries a meter `XferMetrics` does not emit.
