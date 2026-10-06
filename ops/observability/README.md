# Transfer-fee chain observability

`counter-catalog.json` is the source of truth for how legacy SYSOUT counters map
to metrics. `java/observability` (`LegacyCounter`) and
`tools/parity/compare_counters.py` both read or are tested against it.

## Counters

| Step | Program | SYSOUT line | Metric (Micrometer) | Prometheus |
| --- | --- | --- | --- | --- |
| STEP010 | CBXFR01C | `RECORDS READ 000000004` | `xfer.extract.records.read` | `xfer_extract_records_read_total` |
| STEP010 | CBXFR01C | `TRANSFERS SELECTED 000000002` | `xfer.extract.transfers.selected` | `xfer_extract_transfers_selected_total` |
| STEP010 | CBXFR01C | `UNMATCHED CARDS 000000000` | `xfer.extract.cards.unmatched` | `xfer_extract_cards_unmatched_total` |
| STEP020 | XFERFEE | `TRANSFERS POSTED 000000002` | `xfer.posting.transfers.posted` | `xfer_posting_transfers_posted_total` |
| STEP020 | XFERFEE | `TOTAL FEES +00000000650` | `xfer.posting.fees.amount` | `xfer_posting_fees_amount_total` |
| STEP030 | CBXFR03C | `GRAND TOTAL FEE +00000000650` | `xfer.recon.fees.grand_total` | `xfer_recon_fees_grand_total_total` |

All meters carry `chain=xferfee`, `step` and `program` tags. Counters are only
published when the program DISPLAYs them: XFERFEE's abend path and CBXFR03C's
"NO FEE RECORDS" path publish no totals, matching the legacy SYSOUT.

## Return codes

| RC | Legacy cause | Metric effect | Alert | Other |
| --- | --- | --- | --- | --- |
| 0 | normal end | `xfer.step.runs{outcome=ok}` | none | |
| 4 | CBXFR01C unmatched card/account; CBXFR03C no fee records | `xfer.step.runs{outcome=warning}` | warning | each unmatched record emits `TransferRejected` (`xfer.transfers.rejected{reason}`) |
| 8 | XFERFEE `9999-ABEND-PROGRAM` (no fee rule, account not found, I/O or SQL failure) | `xfer.step.runs{outcome=abend}` | critical | in-flight transfer written to the DLQ (`xfer.dlq.entries{reason}`) |

`xfer.step.return_code{step,program}` is a gauge holding the last RC. As in
`tools/runjcl`, the chain stops after the first non-zero RC.

## Dashboards and alerts

- `grafana/xferfee-chain.json`: Grafana dashboard (Prometheus datasource variable).
- `datadog/xferfee-chain.json`: Datadog dashboard (`POST /api/v1/dashboard`).
- `datadog/monitors.json`: Datadog monitors for RC 8, unmatched cards and empty reconciliation.
- `alerts/prometheus-rules.yml`: Prometheus alerting rules with the same thresholds.
