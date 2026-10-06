# legacy-adapter (COG-1240)

Native Java copybook codec and dataset ingress/egress for the coexistence period, when the
Java services and the mainframe still exchange the `XFRDAILY` datasets.

| Package | What |
|---|---|
| `codec` | `CopybookLayout` parses `copybook/*.cpy` (packaged from the repo root, never copied); `CopybookCodec` decodes/encodes `PIC X`, zoned `9`/`S9` (overpunch `{A-I}`/`}J-R` and GnuCOBOL native `p-y` negatives), `COMP-3` and implied decimals. `DecodedRecord` keeps filler bytes and each field's sign style, so decode → encode is byte-for-byte. |
| `io` | `FixedLengthFile`, `LineSequentialFile`, `GenerationDataGroup` (`<DSN>.G0001V00`, written to a temp file and moved into place, so a failed write catalogs nothing). |
| `ingress` | `LegacyFileIngress` reads `DALYTRAN.PS` / `CARDXREF.PS` / `ACCTDATA.PS`, publishes every transaction to `card.transactions` (key = card number, file order) and loads accounts + xrefs into caller-supplied stores. |
| `egress` | `LegacyEgress` writes `XFER.EXTRACT`, `XFER.FEES`, `ACCTDATA.XFER` (+1) and `XFER.RECON.RPT` the way the GnuCOBOL programs do: LOW-VALUE fillers on fresh records, native signs on recomputed balances, source bytes on fields that were only MOVEd. |
| `config` | Spring Boot auto-configuration; `carddemo.legacy-adapter.encoding=ASCII` (GnuCOBOL estate, default) or `EBCDIC` (IBM037, z/OS). Kafka publisher when a `KafkaTemplate` exists, in-memory otherwise. |

Record layouts: `CVTRA06Y` (DALYTRAN, 350), `CVACT03Y` (xref, 50), `CVACT01Y` (account, 300),
`CVXFR01Y` (extract, 120), `CVXFR02Y` (fee, 100).

## Parity

`parity-replay --codec=java` reads the fixture `.PS` files through this module and writes the
candidate datasets through `LegacyEgress`; `--codec=python` (default) keeps the
`tools/parity/copybook.py` path.

```bash
make parity-java CODEC=java          # all cases, legacy-adapter codec
make parity-java-codecs              # python and java candidates must be identical
make java-test                       # includes byte-for-byte fixture round trips
```
