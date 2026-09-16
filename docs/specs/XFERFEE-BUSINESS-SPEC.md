# XFERFEE — Transfer Fee Batch Chain: Business-Logic Specification

Status: **Phase 1 draft — awaiting business-owner approval.**
Scope: the `xferfee` chain (`XFRDAILY` → PROC `XFERFEEP` → `CBXFR01C`, `XFERFEE`, `CBXFR03C`).
Every statement below cites the legacy artefact it was recovered from; behaviour that was
*inferred* rather than read is marked **(inferred)** and repeated in §5.

All line numbers refer to the files at the commit this spec was written against.

---

## 1. Chain overview

### 1.1 Trigger / schedule (Control-M)

Source: `scheduler/XFERFEE.controlm`, folder `DAILY-TransferFeePosting`.

| Order | Control-M job | Member | Description | Window | IN condition | OUT conditions |
|---|---|---|---|---|---|---|
| 1 | `XFREXTR` | `jcl/XFREXTR.jcl` | DAILY transfer extract | `TIMEFROM=2200`, `TIMETO=23:00`, `DAYS=ALL`, every month | `DAILY-TransactionBackup-CLOSEFIL` (ODAT) | `+DAILY-TransferFeePosting-XFREXTR` |
| 2 | `XFERFEE` | `jcl/XFERFEE.jcl` | DAILY transfer fee posting | same | `DAILY-TransferFeePosting-XFREXTR` | `-…-XFREXTR`, `+…-XFERFEE` |
| 3 | `XFRRECON` | `jcl/XFRRECON.jcl` | DAILY transfer reconciliation | same | `DAILY-TransferFeePosting-XFERFEE` | `-…-XFERFEE` |

* Runs every day after the transaction-backup job has closed the daily transaction file.
* `MAXRERUN=5`, `MAXWAIT=7`, non-critical, non-cyclic.
* The Control-M folder schedules the three *stand-alone* JCL members. The estate also ships a
  single-job equivalent, `jcl/XFRDAILY.jcl`, which invokes PROC `XFERFEEP` and runs the same three
  programs as `STEP010/020/030`. `XFRDAILY` is what the runner executes (`tools/runjcl/chains.json`)
  and what the fixtures were recorded from. The two forms are functionally identical except for
  the COND on the reconciliation step (see §1.4).
* `jcl/XFRPURGE.jcl` is a **dead job** ("LAST RUN 03/2019 — REPLACED BY GDG LIMIT"); it is not
  scheduled and not part of the chain.

### 1.2 Job → PROC → steps

`jcl/XFRDAILY.jcl` → `EXEC PROC=XFERFEEP,HLQ=AWS.M2.CARDDEMO` (`jcl/proc/XFERFEEP.prc`):

| Step | Program | Role | Python module (Phase 2) |
|---|---|---|---|
| `STEP010` | `CBXFR01C` | Extract type-08 transfers from the daily transaction file, resolve card→account→book | `xferfee/extract.py` |
| `STEP020` | `XFERFEE` | Look up the fee rule in Db2, compute/cap the fee, update account balances, write fee file and ledger rows | `xferfee/post_fees.py` |
| `STEP030` | `CBXFR03C` | Reconciliation report by book | `xferfee/reconcile.py` |

Setup job `jcl/DEFGDGX.jcl` (IDCAMS) defines the four GDG bases, all `LIMIT(5) SCRATCH`:
`AWS.M2.CARDDEMO.XFER.EXTRACT`, `AWS.M2.CARDDEMO.ACCTDATA.XFER`, `AWS.M2.CARDDEMO.XFER.FEES`,
`AWS.M2.CARDDEMO.XFER.RECON.RPT`.

### 1.3 DD → dataset → layout

| Step | DD | Dataset | Disp | RECFM/LRECL | Layout (copybook) | Direction |
|---|---|---|---|---|---|---|
| STEP010 | `DALYTRAN` | `AWS.M2.CARDDEMO.DALYTRAN.PS` | SHR | FB/350 | `CVTRA05Y` (`TRAN-RECORD`) | in |
| STEP010 | `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.PS` | SHR | FB/50 | `CVACT03Y` (`CARD-XREF-RECORD`) | in |
| STEP010 | `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.PS` | SHR | FB/300 | `CVACT01Y` (`ACCOUNT-RECORD`) | in |
| STEP010 | `XFEREXTR` | `AWS.M2.CARDDEMO.XFER.EXTRACT(+1)` | NEW,CATLG,DELETE | FB/120 | `CVXFR01Y` (`XFER-EXTRACT-RECORD`) | out |
| STEP020 | `XFEREXTR` | `AWS.M2.CARDDEMO.XFER.EXTRACT(0)` | SHR | FB/120 | `CVXFR01Y` | in |
| STEP020 | `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.PS` | SHR | FB/300 | `CVACT01Y` as `OLD-ACCT` | in |
| STEP020 | `ACCTOUT` | `AWS.M2.CARDDEMO.ACCTDATA.XFER(+1)` | NEW,CATLG,DELETE | FB/300 | `CVACT01Y` as `NEW-ACCT` | out |
| STEP020 | `XFERFEE` | `AWS.M2.CARDDEMO.XFER.FEES(+1)` | NEW,CATLG,DELETE | FB/100 | `CVXFR02Y` (`XFER-FEE-RECORD`) | out |
| STEP020 | `DB2PARM` | in-stream `DSN=CARDDEMO PLAN=XFERFEE` | — | — | **not read by the program** (see §5) | — |
| STEP030 | `XFERFEE` | `AWS.M2.CARDDEMO.XFER.FEES(0)` | SHR | FB/100 | `CVXFR02Y` | in |
| STEP030 | `XFERRPT` | `AWS.M2.CARDDEMO.XFER.RECON.RPT(+1)` | NEW,CATLG,DELETE | FBA/133 | `LINE SEQUENTIAL`, `PIC X(133)` | out |
| all | `SYSPRINT`, `SYSOUT` | `SYSOUT=*` | — | — | program `DISPLAY` output | out |
| all | `STEPLIB` | `&HLQ.LOADLIB` | SHR | — | — | — |

`XFRDAILY.jcl` overrides `STEP010.DALYTRAN/XREFFILE/ACCTFILE` and `STEP020.ACCTFILE` with the same
`AWS.M2.CARDDEMO.*.PS` names the PROC already defaults to; the overrides are no-ops.

GDG semantics (JCL and `tools/runjcl/runjcl.py`): `(+1)` allocates the next generation
`<base>.G000nV00`; `(0)` is the current generation. Within one job, a `(+1)` created in an earlier
step is visible as `(0)` in later steps, so `STEP020` reads the extract `STEP010` just wrote and
`STEP030` reads the fee file `STEP020` just wrote. The runner stores the current generation number
in `<base>.gdg`; the fixtures were recorded starting from an empty catalog, so every expected output
is generation `G0001V00`.

Note that `STEP020` writes the updated account master to a **new GDG generation**
(`ACCTDATA.XFER(+1)`) — it never rewrites `ACCTDATA.PS`. Nothing in this chain copies the updated
master back (see §5, Q10).

### 1.4 Condition codes / COND

| Program | RC | When | Source |
|---|---|---|---|
| `CBXFR01C` | 0 | every type-08 transaction resolved to a card and account | `cobol/CBXFR01C.cbl:64-68` |
| `CBXFR01C` | 4 | ≥1 transfer had an unknown card or account (`WS-UNMATCHED-COUNT > 0`) | `CBXFR01C.cbl:64-65` |
| `XFERFEE` | 0 | normal completion | `XFERFEE.cbl:120` |
| `XFERFEE` | 8 | any failure: file open error, DB connect error, no fee rule (SQLCODE 100), any other SQLCODE ≠ 0 on rule lookup or ledger insert, source/target account not in master, write error → `9999-ABEND-PROGRAM` (`STOP RUN`) | `XFERFEE.cbl:100-111,186-194,205-209,226-228,239-242,288-290,292-295` |
| `CBXFR03C` | 0 | ≥1 fee record | `CBXFR03C.cbl:56-68` |
| `CBXFR03C` | 4 | fee file empty — `CBXFR03C: NO FEE RECORDS` | `CBXFR03C.cbl:69-71` |

* `STEP020` has **no COND** — on a real JES it runs even when `STEP010` ends RC 4 (unmatched cards are
  simply absent from the extract). The estate runner (`runjcl.py:339-340`) stops the job at the first
  non-zero RC, so in this environment an RC 4 extract terminates `XFRDAILY` with `MAXCC=0004` and
  STEP020/030 do not run (verified by running the chain with an unknown card). This runner/JES
  difference is recorded in §5, Q9.
* `STEP030 EXEC PGM=CBXFR03C,COND=(4,LT,STEP020)`: bypass STEP030 if `4 < RC(STEP020)`, i.e. only
  when `XFERFEE` abended (RC 8). Since `XFERFEE` only ever returns 0 or 8 the condition is
  equivalent to "skip if posting abended". The stand-alone `XFRRECON.jcl` uses `COND=(4,LT)` with no
  step name (any prior step); same effect.
* Job `MAXCC` = highest step RC. Normal: `0000`. Empty fee file: `0004`.

### 1.5 SYSOUT (DISPLAY) lines

Recorded per step in `sysout/STEPnnn.SYSOUT` (runner, `runjcl.py:326-327`), compared verbatim by the
parity tool:

```
CBXFR01C: CARD NOT FOUND <card 16>            (per unmatched card)      CBXFR01C.cbl:120
CBXFR01C: ACCOUNT NOT FOUND <acct 11>         (per unmatched account)   CBXFR01C.cbl:132-133
CBXFR01C: RECORDS READ 000000004              PIC 9(09)                 CBXFR01C.cbl:61
CBXFR01C: TRANSFERS SELECTED 000000002        PIC 9(09)                 CBXFR01C.cbl:62
CBXFR01C: UNMATCHED CARDS 000000000           PIC 9(09)                 CBXFR01C.cbl:63
XFERFEE: TRANSFERS POSTED 000000002           PIC 9(09)                 XFERFEE.cbl:118
XFERFEE: TOTAL FEES +00000000650              S9(09)V99 COMP-3 shown as sign + 11 digits, no point   XFERFEE.cbl:119
CBXFR03C: GRAND TOTAL FEE +00000000650        same format               CBXFR03C.cbl:67
CBXFR03C: NO FEE RECORDS                                                CBXFR03C.cbl:70
XFERFEE: NO FEE RULE FOR BOOK <book 10> / RULE LOOKUP FAILED <sqlcode> / ACCOUNT NOT FOUND <src> / <tgt> /
LEDGER INSERT FAILED <sqlcode> / DATABASE CONNECT FAILED <sqlcode> / 9999-ABEND-PROGRAM   (error paths)
```

---

## 2. Data contracts

Conventions: offsets are 0-based byte positions within the record; "display" = zoned decimal
(one ASCII digit per byte, sign over-punched in the last byte for `S` pictures); "COMP-3" = packed
decimal, two digits per byte, sign nibble last (`C`=+, `D`=−, `F`=unsigned).
Character fields are space padded. Files are fixed-length with **no** record delimiters except the
report, which is `LINE SEQUENTIAL` (see §2.7).

Sign encoding as actually produced/consumed by the GnuCOBOL runtime in this estate (ASCII, `-std=ibm`):

* Positive zoned result of arithmetic: plain digit `0`–`9` in the last byte (e.g. `898.50` →
  `000000089850`).
* Negative zoned result of arithmetic: last byte `p`–`y` (`0x70`+digit) (e.g. `-4025.00` →
  `00000040250p`) — see `fixtures/xferfee/at_cap/expected/datasets/AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00`.
* Fixture *inputs* use the EBCDIC-style over-punch `{ABCDEFGHI` (+0…+9) / `}JKLMNOPQR` (−0…−9)
  (`tools/fixtures/gen_fixtures.py:13-21`). The runtime reads `{` as +0. Fields that are only
  `MOVE`d between identical pictures are copied byte-for-byte, so an untouched account keeps its
  `…{` sign byte while an updated account is re-encoded with a plain digit. **(observed)** A `}`
  (−0) in the input was read as **positive** by the runtime — see §5, Q3.

### 2.1 `CVTRA05Y` — `TRAN-RECORD` (daily transaction, LRECL 350) — input to STEP010

| Field | PIC | Storage | Offset | Len | Meaning / use in chain |
|---|---|---|---|---|---|
| `TRAN-ID` | X(16) | text | 0 | 16 | Transaction id → `XFR-TRAN-ID` |
| `TRAN-TYPE-CD` | X(02) | text | 16 | 2 | Type; **"08" = transfer** (selection key) |
| `TRAN-CAT-CD` | 9(04) | display unsigned | 18 | 4 | not used |
| `TRAN-SOURCE` | X(10) | text | 22 | 10 | not used |
| `TRAN-DESC` | X(100) | text | 32 | 100 | Free text; **bytes 14–24 (1-based) = target account id** (`TRAN-DESC(14:11)`), i.e. record offset 45–55. Fixture format: `XFER TO ACCT 00000000002` |
| `TRAN-AMT` | S9(09)V99 | display signed, 2 implied decimals | 132 | 11 | Transfer amount → `XFR-TRAN-AMT` |
| `TRAN-MERCHANT-ID` | 9(09) | display | 143 | 9 | not used |
| `TRAN-MERCHANT-NAME` | X(50) | text | 152 | 50 | not used |
| `TRAN-MERCHANT-CITY` | X(50) | text | 202 | 50 | not used |
| `TRAN-MERCHANT-ZIP` | X(10) | text | 252 | 10 | not used |
| `TRAN-CARD-NUM` | X(16) | text | 262 | 16 | Card used → xref lookup; copied to `XFR-CARD-NUM` |
| `TRAN-ORIG-TS` | X(26) | text | 278 | 26 | `yyyy-mm-dd hh:mm:ss.ffffff`; **first 10 bytes = transaction date** |
| `TRAN-PROC-TS` | X(26) | text | 304 | 26 | not used |
| `FILLER` | X(20) | text | 330 | 20 | — |

### 2.2 `CVACT03Y` — `CARD-XREF-RECORD` (card cross-reference, LRECL 50) — input to STEP010

| Field | PIC | Storage | Offset | Len | Meaning |
|---|---|---|---|---|---|
| `XREF-CARD-NUM` | X(16) | text | 0 | 16 | Card number (lookup key) |
| `XREF-CUST-ID` | 9(09) | display | 16 | 9 | not used |
| `XREF-ACCT-ID` | 9(11) | display | 25 | 11 | Owning account → `XFR-SRC-ACCT-ID` |
| `FILLER` | X(14) | text | 36 | 14 | — |

### 2.3 `CVACT01Y` — `ACCOUNT-RECORD` (account master, LRECL 300) — input STEP010/020, output STEP020

`XFERFEE` copies the same layout twice, `REPLACING ==ACCOUNT-RECORD== BY ==OLD-ACCT==` (input) and
`==NEW-ACCT==` (output) (`XFERFEE.cbl:27-32`).

| Field | PIC | Storage | Offset | Len | Meaning / chain use |
|---|---|---|---|---|---|
| `ACCT-ID` | 9(11) | display unsigned | 0 | 11 | Account id (lookup key) |
| `ACCT-ACTIVE-STATUS` | X(01) | text | 11 | 1 | copied through; **not checked** |
| `ACCT-CURR-BAL` | S9(10)V99 | display signed | 12 | 12 | Current balance — debited/credited (BR-11) |
| `ACCT-CREDIT-LIMIT` | S9(10)V99 | display signed | 24 | 12 | copied through; not checked |
| `ACCT-CASH-CREDIT-LIMIT` | S9(10)V99 | display signed | 36 | 12 | copied through |
| `ACCT-OPEN-DATE` | X(10) | text | 48 | 10 | copied through |
| `ACCT-EXPIRAION-DATE` (sic) | X(10) | text | 58 | 10 | copied through (typo is in the copybook and every reference) |
| `ACCT-REISSUE-DATE` | X(10) | text | 68 | 10 | copied through |
| `ACCT-CURR-CYC-CREDIT` | S9(10)V99 | display signed | 78 | 12 | Cycle credits — target gets `+amount` (BR-12) |
| `ACCT-CURR-CYC-DEBIT` | S9(10)V99 | display signed | 90 | 12 | Cycle debits — source gets `+amount+fee` (BR-12) |
| `ACCT-ADDR-ZIP` | X(10) | text | 102 | 10 | copied through |
| `ACCT-GROUP-ID` | X(10) | text | 112 | 10 | **Book id** (`RETAIL`, `INSTL`) — selects the fee rule |
| `FILLER` | X(178) | text | 122 | 178 | not populated on output (`WS-A-FILLER` is never loaded; written as the record area's initial bytes — `0x00` in the fixtures) |

### 2.4 `CVXFR01Y` — `XFER-EXTRACT-RECORD` (extract, LRECL 120) — STEP010 out / STEP020 in

| Field | PIC | Storage | Offset | Len | Populated from |
|---|---|---|---|---|---|
| `XFR-TRAN-ID` | X(16) | text | 0 | 16 | `TRAN-ID` |
| `XFR-TRAN-DT` | X(10) | text | 16 | 10 | `TRAN-ORIG-TS(1:10)` |
| `XFR-SRC-ACCT-ID` | 9(11) | display | 26 | 11 | `XREF-ACCT-ID` of the card |
| `XFR-TGT-ACCT-ID` | 9(11) | display | 37 | 11 | `TRAN-DESC(14:11)` |
| `XFR-BOOK-ID` | X(10) | text | 48 | 10 | `ACCT-GROUP-ID` of the **source** account |
| `XFR-TRAN-AMT` | S9(09)V99 | display signed | 58 | 11 | `TRAN-AMT` (byte copy, sign byte preserved) |
| `XFR-CARD-NUM` | X(16) | text | 69 | 16 | `TRAN-CARD-NUM` (informational; never read downstream) |
| `FILLER` | X(35) | text | 85 | 35 | never set (`0x00` in fixtures) |

### 2.5 `CVXFR02Y` — `XFER-FEE-RECORD` (fee file, LRECL 100) — STEP020 out / STEP030 in

| Field | PIC | Storage | Offset | Len | Populated from |
|---|---|---|---|---|---|
| `XFE-TRAN-ID` | X(16) | text | 0 | 16 | `XFR-TRAN-ID` |
| `XFE-TRAN-DT` | X(10) | text | 16 | 10 | `XFR-TRAN-DT` |
| `XFE-SRC-ACCT-ID` | 9(11) | display | 26 | 11 | `XFR-SRC-ACCT-ID` |
| `XFE-TGT-ACCT-ID` | 9(11) | display | 37 | 11 | `XFR-TGT-ACCT-ID` |
| `XFE-BOOK-ID` | X(10) | text | 48 | 10 | `XFR-BOOK-ID` |
| `XFE-TRAN-AMT` | S9(09)V99 **COMP-3** | packed, 6 bytes, scale 2 | 58 | 6 | `XFR-TRAN-AMT` |
| `XFE-FEE-PCT` | S9(1)V9(6) **COMP-3** | packed, 4 bytes, scale 6 | 64 | 4 | `FEE_PCT` of the selected rule |
| `XFE-FEE-AMT` | S9(09)V99 **COMP-3** | packed, 6 bytes, scale 2 | 68 | 6 | computed (and possibly capped) fee |
| `XFE-CAP-APPLIED` | X(01) | text | 74 | 1 | `Y` if cap replaced the computed fee, else `N` |
| `XFE-RULE-EFF-DT` | X(10) | text | 75 | 10 | `EFF_DT` of the selected rule, `yyyy-mm-dd` |
| `FILLER` | X(15) | text | 85 | 15 | never set (`0x00` in fixtures) |

Example (default fixture, first record): `100.00` → `00 00 00 10 00 0C`; `0.015000` → `00 15 00 0C`
(digits `0015000` + sign C); `1.50` → `00 00 00 00 15 0C`.

### 2.6 `CVXFR09Y` — `XFER-LEGACY-CTL` (dead)

Included in `XFERFEE` WORKING-STORAGE (`XFERFEE.cbl:92`) but **no field is referenced anywhere in
the procedure division**. Fields: `XLC-TELEX-ROUTE-CD X(04)`, `XLC-FEDWIRE-BATCH-NO X(08)`,
`XLC-MICR-LINE X(24)`, `XLC-ACH-SEC-CODE X(03)`, `XLC-LAST-DIAL-TS X(26)`, `FILLER X(15)`.
Header comment: "FOR TELEX FEE ROUTING (PROJECT 91-114), DATED 1991, RETAINED FOR HISTORICAL
CONTROL RECORD COMPATIBILITY". Not part of the contract; see §5, Q11.

### 2.7 Reconciliation report (`XFERRPT`, LRECL 133, RECFM=FBA)

`CBXFR03C` declares the file `LINE SEQUENTIAL` with `01 XFERRPT-REC PIC X(133)` (`CBXFR03C.cbl:14-21`).
The GnuCOBOL runtime therefore writes each line as text with **trailing spaces removed and a `\n`
terminator**, and no ASA carriage-control byte despite `RECFM=FBA`. Fixture files confirm this
(no line is 133 bytes). Line formats are in BR-16.

### 2.8 Db2 tables

#### `CTL_XFER_PARM` — fee rules (`db2/ddl/CTL_XFER_PARM.sql`)

| Column | Type | Meaning |
|---|---|---|
| `BOOK_ID` | CHAR(10) NOT NULL | Book / account group (`RETAIL    `, `INSTL     `) |
| `FEE_PCT` | DECIMAL(7,6) NOT NULL | Fee rate as a fraction (0.012500 = 1.25 %) |
| `FEE_CAP` | DECIMAL(11,2) NOT NULL | Maximum fee per transfer |
| `EFF_DT` | DATE NOT NULL | First day the rule applies (inclusive) |
| `EXP_DT` | DATE NOT NULL | First day the rule no longer applies (exclusive) |
| PK | (`BOOK_ID`, `EFF_DT`) | |

Seed (`db2/data/CTL_XFER_PARM.sql`, identical in every fixture's `db2_before/CTL_XFER_PARM.csv`):

| BOOK_ID | FEE_PCT | FEE_CAP | EFF_DT | EXP_DT |
|---|---|---|---|---|
| RETAIL | 0.012500 | 25.00 | 2020-01-01 | 2024-06-15 |
| RETAIL | 0.015000 | 25.00 | 2024-06-15 | 9999-12-31 |
| INSTL | 0.005000 | 500.00 | 2020-01-01 | 9999-12-31 |

Read-only for this chain.

#### `XFER_FEE_LEDGER` — posted fees (`db2/ddl/XFER_FEE_LEDGER.sql`)

| Column | Type | Written from |
|---|---|---|
| `TRAN_ID` | CHAR(16) PRIMARY KEY | `WS-XFE-TRAN-ID` |
| `TRAN_DT` | DATE NOT NULL | `CAST(:WS-XFE-TRAN-DT AS DATE)` |
| `SRC_ACCT_ID` | DECIMAL(11,0) NOT NULL | `WS-XFE-SRC-ACCT-ID` |
| `TGT_ACCT_ID` | DECIMAL(11,0) NOT NULL | `WS-XFE-TGT-ACCT-ID` |
| `BOOK_ID` | CHAR(10) NOT NULL | `WS-XFE-BOOK-ID` |
| `TRAN_AMT` | DECIMAL(11,2) NOT NULL | `WS-XFE-TRAN-AMT` |
| `FEE_AMT` | DECIMAL(11,2) NOT NULL | `WS-XFE-FEE-AMT` |
| `CAP_APPLIED` | CHAR(1) NOT NULL | `WS-XFE-CAP-APPLIED` |
| `POSTED_TS` | TIMESTAMP DEFAULT CURRENT_TIMESTAMP | not supplied (DB default; excluded from parity) |

The runner truncates this table before each run (`runjcl.py --db-reset`); the JCL itself has no
purge step.

### 2.9 Exact SQL issued by `XFERFEE` (Open COBOL ESQL → PostgreSQL)

Connection (`XFERFEE.cbl:95-97,104-107`): credentials are taken from environment variables
`OCDB_NAME`, `OCDB_USER`, `OCDB_PASS` (`docker-compose.yml`: `carddemo`/`carddemo`/`carddemo`,
host `db`, port 5432), **not** from the `DB2PARM` DD.

```sql
EXEC SQL CONNECT :WS-DB-USER IDENTIFIED BY :WS-DB-PASS USING :WS-DB-NAME END-EXEC
```

Rule lookup (`XFERFEE.cbl:178-185`), once per extract record, singleton `SELECT … INTO`:

```sql
SELECT FEE_PCT, FEE_CAP, EFF_DT
  INTO :WS-FEE-PCT,          -- PIC S9(1)V9(6) COMP-3
       :WS-FEE-CAP,          -- PIC S9(09)V99  COMP-3
       :WS-RULE-EFF-DT       -- PIC X(10)  (DATE rendered yyyy-mm-dd)
  FROM CTL_XFER_PARM
 WHERE BOOK_ID = :WS-XFR-BOOK-ID                       -- PIC X(10), space padded, e.g. 'RETAIL    '
   AND EFF_DT <= CAST(:WS-XFR-TRAN-DT AS DATE)         -- PIC X(10) 'yyyy-mm-dd'
   AND EXP_DT >  CAST(:WS-XFR-TRAN-DT AS DATE)
```
`SQLCODE 100` (no row) → RC 8 abend; any other non-zero `SQLCODE` (including −811 "more than one
row" if rules overlap) → RC 8 abend.

Ledger insert (`XFERFEE.cbl:229-238`), once per extract record:

```sql
INSERT INTO XFER_FEE_LEDGER
    (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED)
VALUES
    (:WS-XFE-TRAN-ID,                  -- X(16)
     CAST(:WS-XFE-TRAN-DT AS DATE),    -- X(10)
     :WS-XFE-SRC-ACCT-ID,              -- 9(11)
     :WS-XFE-TGT-ACCT-ID,              -- 9(11)
     :WS-XFE-BOOK-ID,                  -- X(10)
     :WS-XFE-TRAN-AMT,                 -- S9(09)V99 COMP-3
     :WS-XFE-FEE-AMT,                  -- S9(09)V99 COMP-3
     :WS-XFE-CAP-APPLIED)              -- X(01)
```

Commit (`XFERFEE.cbl:114-116`): a single `EXEC SQL COMMIT END-EXEC` after **all** transfers are
posted and the account master has been written. There is no explicit `ROLLBACK` on abend; the
process ends with `STOP RUN` and the uncommitted inserts are discarded when the connection drops.

---

## 3. Business rules

Each rule: statement, source, inputs, outputs, edge cases.

### BR-1 Transaction selection (extract)
A daily transaction is a transfer candidate **iff `TRAN-TYPE-CD = "08"`**. Every record is counted
in `RECORDS READ`; only type-08 records proceed to card resolution. All other types are ignored
silently.
Source: `cobol/CBXFR01C.cbl:98-109`.
Inputs: `DALYTRAN` records. Outputs: `WS-READ-COUNT` (+1 per record); candidates to BR-2.
Edge: the check is an exact 2-byte compare — `"8 "`, `" 8"` or lower-case would not match. Input
order is preserved throughout the chain; nothing is sorted.

### BR-2 Card → source account resolution
The source account of a transfer is the `XREF-ACCT-ID` of the **first** xref row whose
`XREF-CARD-NUM` equals `TRAN-CARD-NUM` (exact 16-byte compare). If no row matches: display
`CBXFR01C: CARD NOT FOUND <card>`, add 1 to `WS-UNMATCHED-COUNT`, and **drop the transaction**
(no extract record).
Source: `CBXFR01C.cbl:111-121`.
Inputs: `TRAN-CARD-NUM`, xref table. Outputs: `XFR-SRC-ACCT-ID` or unmatched count.
Edge: xref table holds at most **500** rows — rows beyond 500 are silently ignored
(`CBXFR01C.cbl:75-81`). Duplicate cards: first wins.

### BR-3 Source account → book resolution
The book of a transfer is the `ACCT-GROUP-ID` of the **first** account-master row whose `ACCT-ID`
equals the resolved source account. If not found: display `CBXFR01C: ACCOUNT NOT FOUND <acct>`,
add 1 to `WS-UNMATCHED-COUNT`, drop the transaction. **The target account is not validated in the
extract.**
Source: `CBXFR01C.cbl:123-134`, table load `84-97`.
Outputs: `XFR-BOOK-ID`. Edge: 500-row cap; first match wins. The book is always taken from the
**source** account, even when the target belongs to another book (verified: an INSTL→RETAIL
transfer is charged the INSTL rule).

### BR-4 Extract record contents
For each resolved transfer one `CVXFR01Y` record is written:
`XFR-TRAN-ID ← TRAN-ID`, `XFR-TRAN-DT ← TRAN-ORIG-TS(1:10)`, `XFR-TRAN-AMT ← TRAN-AMT`,
`XFR-CARD-NUM ← TRAN-CARD-NUM`, `XFR-TGT-ACCT-ID ← TRAN-DESC(14:11)`, plus `XFR-SRC-ACCT-ID` (BR-2)
and `XFR-BOOK-ID` (BR-3). `WS-SELECT-COUNT` +1.
Source: `CBXFR01C.cbl:136-142`.
Edge: the target account is **positional text** taken from the description (fixture form
`XFER TO ACCT nnnnnnnnnnn`); a description in any other shape yields a garbage target that only
fails in STEP020 (BR-10). `TRAN-ORIG-TS` is assumed `yyyy-mm-dd…`; the date is not validated.

### BR-5 Extract return code
RC 4 if `WS-UNMATCHED-COUNT > 0`, else RC 0. Displays `RECORDS READ`, `TRANSFERS SELECTED`,
`UNMATCHED CARDS` (the last counter includes unmatched *accounts* too, despite its label).
Source: `CBXFR01C.cbl:61-68`.

### BR-6 Account master load (posting)
`XFERFEE` reads the whole `ACCTFILE` into an in-memory table of at most **500** accounts, preserving
file order; every field except `FILLER` is copied. Later rows are silently ignored.
Source: `XFERFEE.cbl:122-155`.
Edge: the input open status is checked (`100-102`); an empty master is not an error by itself but
every transfer will then fail BR-10.

### BR-7 Fee-rule selection
For each extract record select the single `CTL_XFER_PARM` row with `BOOK_ID = XFR-BOOK-ID` and
`EFF_DT <= tran_dt < EXP_DT` (effective date **inclusive**, expiry date **exclusive**).
Consequently on a boundary date the **new** rule wins: on 2024-06-15 RETAIL pays 1.5 %, not 1.25 %
(fixture `rate_change`: 06-14 → 1.25, 06-15 → 1.50, 06-16 → 1.50).
Source: `XFERFEE.cbl:178-185`; fixture `rate_change`.
Inputs: `XFR-BOOK-ID`, `XFR-TRAN-DT`. Outputs: `WS-FEE-PCT`, `WS-FEE-CAP`, `WS-RULE-EFF-DT`.
Edge cases:
* No matching row (`SQLCODE 100`): `XFERFEE: NO FEE RULE FOR BOOK <book>`, RC 8 abend
  (`186-189`). Nothing is committed.
* More than one matching row (overlapping rules; the PK only prevents duplicate `EFF_DT`):
  singleton SELECT returns an error, `RULE LOOKUP FAILED`, RC 8 abend (`191-194`).
* `XFR-TRAN-DT` that is not a valid date makes the `CAST` fail → RC 8 abend.
* The lookup is done **even for zero-amount transfers**; a zero transfer for a book with no rule
  still abends.

### BR-8 Fee computation and rounding
If `XFR-TRAN-AMT ≠ 0`: `WS-FEE-AMT = ROUNDED(XFR-TRAN-AMT × WS-FEE-PCT)` where `WS-FEE-AMT` is
`PIC S9(09)V99 COMP-3`. The exact product has 8 decimal places (2 + 6); `ROUNDED` with no mode
keeps **2 decimals, rounding half away from zero** (nearest-away-from-zero, the COBOL default).
Retained precision is therefore exactly cents; no more precision survives to any output, total, or
ledger row.
Source: `XFERFEE.cbl:196-198`; fixture `half_cent` proves the tie-breaking:
`2.00×0.0125 = 0.025 → 0.03`, `5.20×0.0125 = 0.065 → 0.07`, `3.00×0.015 = 0.045 → 0.05`,
`7.00×0.015 = 0.105 → 0.11`, `11.00×0.015 = 0.165 → 0.17`, `5.00×0.005 = 0.025 → 0.03`
(banker's rounding would give 0.02/0.06/0.04/0.10/0.16/0.02 — every one differs).
Also observed: `50.00×0.0125 = 0.625 → 0.63`.
Edge: a negative amount produces a negative fee (`ROUNDED` is symmetric). A product exceeding
`999,999,999.99` would be high-order truncated (no `ON SIZE ERROR`); not reachable with the
seeded rates (max 9.999999 × 999,999,999.99 would need a rate ≥ 1).

### BR-9 Cap and cap-applied flag
After BR-8, if `WS-FEE-AMT > WS-FEE-CAP` (strictly greater) then `WS-FEE-AMT ← WS-FEE-CAP` and
`XFE-CAP-APPLIED ← "Y"`; otherwise the flag stays `"N"`. The flag is reset to `"N"` at the start
of every transfer.
Source: `XFERFEE.cbl:176,199-202`; fixture `at_cap` (5000×1.25 % = 62.50 → 25.00 `Y`;
200000×0.5 % = 1000.00 → 500.00 `Y`).
Edge: a fee **exactly equal** to the cap is *not* capped and the flag is `N` (verified:
`2000.00 × 0.0125 = 25.00` → `25.00`, `N`). Negative fees are never capped (they are `< cap`).
The cap applies per transfer, not per account or per day.

### BR-10 Zero-amount transfers
If `XFR-TRAN-AMT = 0` the fee computation and cap test are skipped: fee = 0.00, flag `N`. The
transfer is otherwise processed normally: rule lookup (BR-7), account lookup, balance arithmetic
(adding 0), fee record, ledger row, counters.
Source: `XFERFEE.cbl:175-176,196-203`; fixture `zero_amount`.
Edge: balance fields of the two accounts are re-encoded (sign byte becomes a plain digit) even
though their value is unchanged — visible in the fixture as `000000100000` vs the input `00000010000{`.

### BR-11 Source/target account resolution (posting)
Scan the in-memory master; `WS-SRC-SUB` is the index of the **last** row with
`WS-A-ID = XFR-SRC-ACCT-ID`, `WS-TGT-SUB` the **last** row with `WS-A-ID = XFR-TGT-ACCT-ID`.
If either is 0: `XFERFEE: ACCOUNT NOT FOUND <src> / <tgt>`, RC 8 abend.
Source: `XFERFEE.cbl:204-209,245-259`.
Edge: last-match here vs first-match in the extract (BR-2/3) — only matters with duplicate account
ids. Source = target is allowed (both subscripts equal).

### BR-12 Balance and cycle updates
In this order, on the in-memory rows (`PIC S9(10)V99`):
```
source.ACCT-CURR-BAL        -= XFR-TRAN-AMT
source.ACCT-CURR-BAL        -= WS-FEE-AMT
target.ACCT-CURR-BAL        += XFR-TRAN-AMT
target.ACCT-CURR-CYC-CREDIT += XFR-TRAN-AMT
source.ACCT-CURR-CYC-DEBIT  += XFR-TRAN-AMT + WS-FEE-AMT
```
The fee is charged to the source only; the target receives the gross amount. No credit-limit,
active-status or overdraft check is made; balances may go negative (fixture `at_cap`: account 1
ends at −4025.00).
Source: `XFERFEE.cbl:210-214`.
Edge: when source = target the net effect is `BAL −= fee`, `CYC-CREDIT += amt`,
`CYC-DEBIT += amt + fee` (verified: 750.00, 50.00 @1.25 % → 749.37 / 50.00 / 50.63). No
`ON SIZE ERROR`; overflow past 10 integer digits truncates high-order digits.

### BR-13 Fee record and ledger insert
For every posted transfer, in this order: write one `CVXFR02Y` record (fields per §2.5:
`XFE-FEE-PCT` = rule rate, `XFE-RULE-EFF-DT` = rule `EFF_DT`, `XFE-FEE-AMT` = final fee), then
`INSERT` one `XFER_FEE_LEDGER` row with the same id/date/accounts/book/amount/fee/flag (the rate
and rule date are **not** stored in the ledger). Write failure or `SQLCODE ≠ 0` → RC 8 abend.
Source: `XFERFEE.cbl:215-242`.
Edge: `TRAN_ID` is the ledger PK — a transaction id already present (re-run without purge, or a
duplicate id in the day's file) fails the insert → `LEDGER INSERT FAILED`, RC 8, and the whole
run's ledger rows are lost (no commit). Fee records already written to the GDG generation remain.

### BR-14 Posting totals, master write-back, commit, return code
`WS-TRANSFER-COUNT` +1 and `WS-FEE-TOTAL += WS-FEE-AMT` per transfer (`243-244`). After the extract
is exhausted, **every** loaded account (updated or not) is written to `ACCTOUT` in load order
(`260-291`), then `COMMIT`, then `TRANSFERS POSTED nnnnnnnnn` / `TOTAL FEES ±ddddddddddd` are displayed
and RC 0 is returned (`113-121`). An empty extract yields an empty fee file, an unchanged master
copy, `TRANSFERS POSTED 000000000`, `TOTAL FEES +00000000000`, RC 0.
Source: `XFERFEE.cbl:112-121,243-244,260-291`.
Edge: accounts are written from the working table, so `FILLER` (bytes 122–299) is not copied from
the input — output filler is the record area's initial content (`0x00` in this runtime).

### BR-15 Reconciliation totals and subtotal breaks
Reading the fee file in order: `WS-COUNT` +1, `WS-GRAND-AMT/WS-BOOK-AMT += XFE-TRAN-AMT`,
`WS-GRAND-FEE/WS-BOOK-FEE += XFE-FEE-AMT` (all `S9(09)V99 COMP-3`). **Before** printing a detail
line, if the record's `XFE-BOOK-ID` differs from the previous record's book (`WS-LAST-BOOK`, and
`WS-LAST-BOOK ≠ SPACES`), a subtotal line for the previous book is printed and the book
accumulators are reset. After the last record, if `WS-COUNT > 0`, a final subtotal and the grand
total are printed.
Source: `CBXFR03C.cbl:50-66,75-85,93-102`.
Edge: the file is **not sorted** — books that interleave produce a subtotal at every change
(verified: RETAIL, INSTL, RETAIL, INSTL → four subtotal lines). A record whose book is all spaces
never starts a "previous book" and would merge into the next book's subtotal.

### BR-16 Report layout
Lines (leading single space is part of the text; trailing spaces are stripped on write; edited
pictures right-justify with leading spaces):
```
" TRANSFER FEE RECONCILIATION"
" TRANSACTION       DATE       BOOK       AMOUNT          FEE"
" " + TRAN-ID(16) + " " + TRAN-DT(10) + " " + BOOK(10) + " " + Z(8)9.99-(amt) + " " + Z(8)9.99-(fee)
" BOOK " + LAST-BOOK(10) + " SUBTOTAL AMOUNT " + Z(8)9.99-(book amt) + " FEE " + Z(8)9.99-(book fee)
" GRAND TOTAL COUNT " + Z(8)9(count) + " AMOUNT " + Z(8)9.99-(grand amt) + " FEE " + Z(8)9.99-(grand fee)
```
`Z(8)9.99-` is 13 characters: zero-suppressed integer part (min one digit), `.`, two decimals, then
`-` for negative or a space for non-negative (the trailing space is what gets stripped at end of
line). `Z(8)9` is 9 characters. `LINE SEQUENTIAL` output: each line = text without trailing spaces
+ `\n`; the 133-byte LRECL is never padded.
Source: `CBXFR03C.cbl:30-37,41-49,58-66,86-92,94-100`; fixtures.
Example (default): ` TRN0000000000002 2024-06-20 RETAIL           100.00          1.50`.

### BR-17 Reconciliation return code / NO FEE RECORDS
If at least one fee record was read: display `CBXFR03C: GRAND TOTAL FEE ±ddddddddddd`, RC 0.
If the fee file is empty: the two header lines are still written, no totals, display
`CBXFR03C: NO FEE RECORDS`, **RC 4** → job `MAXCC=0004`.
Source: `CBXFR03C.cbl:56-72`. Verified by running the chain with only non-transfer transactions:
STEP010 RC 0 (0 selected), STEP020 RC 0 (0 posted), STEP030 RC 4, MAXCC 4. None of the seven
recorded fixtures exercises this path.

### BR-18 Step ordering and COND
STEP010 → STEP020 → STEP030 sequentially; STEP030 is bypassed only if STEP020 RC > 4 (i.e. abend);
STEP020 runs regardless of STEP010's RC on JES (the estate runner instead stops the job at any
non-zero RC). Source: `jcl/proc/XFERFEEP.prc`, `runjcl.py:290-340`. See §1.4.

---

## 4. Worked examples (recorded fixtures)

Common inputs to all seven cases (`tools/fixtures/gen_fixtures.py:234-246`): accounts 1–5 book
`RETAIL` with balances 1000/500/750/250/1250; accounts 6–8 book `INSTL` with 2000/1500/900; all
cycle credit/debit 0; cards `1000000000000001`…`…008` map to accounts 1…8; fee rules per §2.8;
ledger empty before the run. All cases end `STEP010=0, STEP020=0, STEP030=0, MAXCC=0`.
"Balances" below are the changed accounts only (`ACCTDATA.XFER`: bal / cyc-credit / cyc-debit);
untouched accounts are written unchanged.

### 4.1 `default` — BR-1…9, 11–18
| Tran | Type | Amt | Card→Src | Tgt | Date | Selected? |
|---|---|---|---|---|---|---|
| TRN…0001 | 01 | 42.00 | 3 | — | 06-05 | no (BR-1) |
| TRN…0002 | 08 | 100.00 | 1 | 2 | 2024-06-20 | yes |
| TRN…0003 | 08 | 1000.00 | 6 | 7 | 2024-06-21 | yes |
| TRN…0004 | 01 | 18.50 | 5 | — | 06-29 | no |

| Tran | Book | Rule (pct, eff) | Fee | Cap | Ledger row |
|---|---|---|---|---|---|
| TRN…0002 | RETAIL | 0.015000, 2024-06-15 | 1.50 | N | `TRN0000000000002,2024-06-20,1,2,RETAIL,100.00,1.50,N` |
| TRN…0003 | INSTL | 0.005000, 2020-01-01 | 5.00 | N | `TRN0000000000003,2024-06-21,6,7,INSTL,1000.00,5.00,N` |

Balances: acct 1 → 898.50 / 0 / 101.50; acct 2 → 600.00 / 100.00 / 0; acct 6 → 995.00 / 0 / 1005.00;
acct 7 → 2500.00 / 1000.00 / 0.
SYSOUT: `RECORDS READ 000000004`, `TRANSFERS SELECTED 000000002`, `UNMATCHED CARDS 000000000`,
`TRANSFERS POSTED 000000002`, `TOTAL FEES +00000000650`, `GRAND TOTAL FEE +00000000650`.
Report:
```
 TRANSFER FEE RECONCILIATION
 TRANSACTION       DATE       BOOK       AMOUNT          FEE
 TRN0000000000002 2024-06-20 RETAIL           100.00          1.50
 BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50
 TRN0000000000003 2024-06-21 INSTL           1000.00          5.00
 BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00
 GRAND TOTAL COUNT         2 AMOUNT      1100.00  FEE         6.50
```

### 4.2 `under_cap` — BR-7 (old RETAIL rule), BR-8, BR-9 (not capped), BR-12–17
| Tran | Amt | Src→Tgt | Date | Book | Pct / eff | Fee | Cap |
|---|---|---|---|---|---|---|---|
| TRN…0001 | 100.00 | 1→2 | 2024-06-05 | RETAIL | 0.012500 / 2020-01-01 | 1.25 | N |
| TRN…0002 | 1000.00 | 6→7 | 2024-06-05 | INSTL | 0.005000 / 2020-01-01 | 5.00 | N |

Balances: 1 → 898.75 / 0 / 101.25; 2 → 600.00 / 100.00 / 0; 6 → 995.00 / 0 / 1005.00; 7 → 2500.00 / 1000.00 / 0.
Totals: `TOTAL FEES +00000000625`; report subtotals RETAIL 100.00/1.25, INSTL 1000.00/5.00, grand
`COUNT 2 AMOUNT 1100.00 FEE 6.25`.

### 4.3 `at_cap` — BR-9 (cap applied, flag Y), negative balance in BR-12
| Tran | Amt | Src→Tgt | Date | Book | Pct | Raw fee | Fee | Cap |
|---|---|---|---|---|---|---|---|---|
| TRN…0001 | 5000.00 | 1→2 | 2024-06-05 | RETAIL | 0.012500 | 62.50 | **25.00** | **Y** |
| TRN…0002 | 200000.00 | 6→7 | 2024-06-05 | INSTL | 0.005000 | 1000.00 | **500.00** | **Y** |

Balances: 1 → **−4025.00** (`00000040250p`) / 0 / 5025.00; 2 → 5500.00 / 5000.00 / 0;
6 → **−198500.00** / 0 / 200500.00; 7 → 201500.00 / 200000.00 / 0.
Ledger: `…,5000.00,25.00,Y` and `…,200000.00,500.00,Y`. `TOTAL FEES +00000052500`; grand
`COUNT 2 AMOUNT 205000.00 FEE 525.00`.

### 4.4 `rate_change` — BR-7 boundary semantics
| Tran | Amt | Src→Tgt | Date | Rule chosen | Fee |
|---|---|---|---|---|---|
| TRN…0001 | 100.00 | 1→2 | 2024-06-14 | RETAIL 0.012500 eff 2020-01-01 (exp 2024-06-15 > 06-14) | 1.25 |
| TRN…0002 | 100.00 | 1→2 | **2024-06-15** | RETAIL 0.015000 eff **2024-06-15** (`EFF_DT <=` inclusive; old row excluded by `EXP_DT >`) | **1.50** |
| TRN…0003 | 100.00 | 1→2 | 2024-06-16 | RETAIL 0.015000 | 1.50 |

Fee records carry `XFE-RULE-EFF-DT` `2020-01-01`, `2024-06-15`, `2024-06-15`.
Balances: 1 → 695.75 / 0 / 304.25; 2 → 800.00 / 300.00 / 0. `TOTAL FEES +00000000425`; one RETAIL
subtotal `300.00 / 4.25`; grand `COUNT 3 AMOUNT 300.00 FEE 4.25`.

### 4.5 `zero_amount` — BR-10
| Tran | Amt | Src→Tgt | Date | Rule | Fee | Cap |
|---|---|---|---|---|---|---|
| TRN…0001 | 0.00 | 1→2 | 2024-06-05 | RETAIL 0.012500 (lookup still performed) | 0.00 | N |

Fee record written (`XFE-FEE-PCT 0.0125`, `XFE-FEE-AMT 0`), ledger row `…,0.00,0.00,N`.
Balances numerically unchanged (1 → 1000.00 / 0 / 0.00; 2 → 500.00 / 0.00 / 0) but re-encoded
with plain-digit sign bytes. `TRANSFERS POSTED 000000001`, `TOTAL FEES +00000000000`,
report detail `0.00 / 0.00`, grand `COUNT 1 AMOUNT 0.00 FEE 0.00`, RC 0 (not the NO FEE RECORDS path).

### 4.6 `non_transfer` — BR-1 (types 01, 02, 05 ignored)
Four records; only TRN…0004 (type 08, 100.00, 1→2, 2024-06-05) is selected → RETAIL 1.25 %, fee
1.25, `N`. `RECORDS READ 000000004`, `TRANSFERS SELECTED 000000001`. Balances 1 → 898.75 / 0 /
101.25; 2 → 600.00 / 100.00 / 0. Grand `COUNT 1 AMOUNT 100.00 FEE 1.25`.

### 4.7 `half_cent` — BR-8 rounding ties, BR-15 single subtotal per contiguous book
| Tran | Amt | Src→Tgt | Date | Pct | Exact | Fee |
|---|---|---|---|---|---|---|
| TRN…0001 | 2.00 | 1→2 | 06-05 | 0.0125 | 0.02500 | 0.03 |
| TRN…0002 | 5.20 | 2→3 | 06-05 | 0.0125 | 0.06500 | 0.07 |
| TRN…0003 | 3.00 | 3→4 | 06-15 | 0.0150 | 0.04500 | 0.05 |
| TRN…0004 | 7.00 | 4→5 | 06-16 | 0.0150 | 0.10500 | 0.11 |
| TRN…0005 | 11.00 | 5→1 | 06-20 | 0.0150 | 0.16500 | 0.17 |
| TRN…0006 | 5.00 | 6→7 | 06-05 | 0.0050 | 0.02500 | 0.03 |

Balances: 1 → 1008.97 / 11.00 / 2.03; 2 → 496.73 / 2.00 / 5.27; 3 → 752.15 / 5.20 / 3.05;
4 → 245.89 / 3.00 / 7.11; 5 → 1245.83 / 7.00 / 11.17; 6 → 1994.97 / 0 / 5.03; 7 → 1505.00 / 5.00 / 0.
`TOTAL FEES +00000000046`; RETAIL subtotal `28.20 / 0.43`, INSTL `5.00 / 0.03`, grand
`COUNT 6 AMOUNT 33.20 FEE 0.46`.

### 4.8 Additional behaviours verified against the COBOL (not in the recorded fixtures)
Run in the Compose estate while writing this spec; to be used as extra Phase 2 tests only if the
owner agrees they are in scope:
* Fee exactly equal to cap (`2000.00 × 0.0125 = 25.00`) → fee 25.00, flag `N` (BR-9).
* Source = target (acct 3, 50.00) → 749.37 / 50.00 / 50.63 (BR-12).
* Cross-book transfer 8 (INSTL) → 4 (RETAIL) → INSTL rule 0.5 % (BR-3).
* Interleaved books → four subtotal lines (BR-15).
* Only non-transfer input → `NO FEE RECORDS`, STEP030 RC 4, MAXCC 4 (BR-17).
* Unknown card → `CARD NOT FOUND`, STEP010 RC 4; the runner stopped the job (BR-5/BR-18).

---

## 5. Ambiguities / questions for the business owner

**Q1 — Contradictory fee history comments.** `XFERFEE.cbl:171-173` says "03/17/87 FEE IS A FLAT
$2.50 PER WIRE, NO CAP. DO NOT CHANGE WITHOUT TREASURY SIGN-OFF" and "11/02/93 CHANGED TO 1% PER
NYCE". The code applies a table-driven percentage (1.25 % / 1.5 % / 0.5 %) with a cap. The
comments are treated as obsolete; please confirm the table is the source of truth.

**Q2 — Fee == cap.** The cap is applied only when the fee is *strictly* greater than the cap
(BR-9). A fee exactly at the cap is reported as `CAP_APPLIED = N`. Intentional?

**Q3 — Negative / signed transaction amounts.** Nothing rejects a negative `TRAN-AMT`; it would
produce a negative fee, *increase* the source balance and *decrease* the target. Additionally, the
fixture generator's negative over-punch (`}JKLMNOPQR`) is read as **positive** by this GnuCOBOL/ASCII
runtime (observed: `−100.00` encoded as `0000010000}` posted as `+100.00`). Only `p`–`y` are
negative for the runtime. Which convention (if any) should the port honour for negative inputs, or
should negative transfers be rejected?

**Q4 — Target account taken from `TRAN-DESC(14:11)`.** The target account is positional text in a
free-text description (BR-4). Is the `XFER TO ACCT nnnnnnnnnnn` shape guaranteed upstream? The
extract does not validate it; a malformed description only surfaces as an RC 8 abend in STEP020.

**Q5 — Target-account validation happens late.** An unknown *source* account drops the
transaction with RC 4 (BR-3), but an unknown *target* account abends the whole posting step with
RC 8 after some fee records may already have been written to the new GDG generation (BR-11/13).
Confirm this asymmetry is desired.

**Q6 — 500-row tables.** Xref and account tables are capped at 500 rows in both programs; extra
rows are silently ignored, leading to spurious "not found" results on larger files. Is 500 a real
production limit or an estate artefact? The port will not impose it unless told to.

**Q7 — Duplicate account ids.** Extract uses first match, posting uses last match (BR-2/3 vs
BR-11). Assumed impossible in real data — confirm.

**Q8 — No status / limit checks.** `ACCT-ACTIVE-STATUS`, `ACCT-CREDIT-LIMIT` and
`ACCT-CASH-CREDIT-LIMIT` are copied through but never tested; balances may go negative (`at_cap`).
Confirm no overdraft/inactive-account rule is expected.

**Q9 — RC 4 from the extract.** On JES, STEP020 has no COND and would post the resolvable
transfers even when some cards were unmatched (MAXCC 4). The estate runner stops the job instead.
Which behaviour should the Python chain runner reproduce? (Proposed: JES semantics — continue —
since that is what the JCL says; the parity fixtures are unaffected either way.)

**Q10 — Account master write-back.** STEP020 writes the updated master to `ACCTDATA.XFER(+1)` and
never touches `ACCTDATA.PS`; nothing in the chain or scheduler copies it back, so the next day's run
would start from stale balances. Out of scope for the port, but flagged.

**Q11 — Dead fields / dead copybook.** `CVXFR09Y` (`XFER-LEGACY-CTL`, telex/fedwire/ACH, 1991) is
included but never referenced; `XFR-CARD-NUM` and the three `FILLER`s are written but never read;
`DB2PARM` DD (`DSN=CARDDEMO PLAN=XFERFEE`) is not read (connection comes from `OCDB_*` env vars);
`XFRPURGE.jcl` is a dead job. The port will keep the *layouts* byte-compatible (fillers written as
`0x00` to match the recorded fixtures — the comparator ignores FILLER content) but implement no
logic for these.

**Q12 — `UNMATCHED CARDS` counter** also counts unmatched *accounts* (BR-5). Keep the label as is
for SYSOUT parity?

**Q13 — Reconciliation subtotal breaks on unsorted input** (BR-15). Should the report keep
"subtotal on every change" semantics (byte parity) rather than sorting by book?

**Q14 — Ledger re-runs.** `TRAN_ID` is the PK and the JCL has no purge, so re-running a day abends
with `LEDGER INSERT FAILED` after writing partial fee records (BR-13). Confirm no idempotency /
`MERGE` behaviour is wanted in the port.

**Q15 — Report RECFM.** The DD says `RECFM=FBA,LRECL=133` but the program writes `LINE SEQUENTIAL`
text (no carriage-control byte, trailing spaces stripped, `\n`). The port will match the recorded
files, not the DCB.

**Q16 — Unchecked COMMIT and file status.** `XFERFEE.cbl:114-121` does not test `SQLCODE` after
`COMMIT` (a failed commit still ends with RC 0 and both output generations written), and none of the
programs test their `FILE STATUS` fields, so I/O errors surface as a runtime crash rather than RC 8.
The port preserves this (only CONNECT / rule SELECT / ledger INSERT failures route to RC 8). Confirm
whether a commit failure should become RC 8 in the port.

**(inferred)** items: sign-byte encodings in §2 preamble; `ROUNDED` = half-away-from-zero (proved by
`half_cent`, not by a compiler option in the source); `SQLCODE` for multi-row SELECT causing abend
(from ESQL semantics, not observed).

---

## 6. Proposed Python design (Phase 2)

Package `python/xferfee/` (Python 3.10+, `decimal.Decimal` everywhere money or rates appear,
no floats):

| Module | Legacy | Responsibility |
|---|---|---|
| `layouts.py` | copybooks | `@dataclass` per record (`TranRecord`/CVTRA05Y, `CardXref`/CVACT03Y, `AccountRecord`/CVACT01Y, `ExtractRecord`/CVXFR01Y, `FeeRecord`/CVXFR02Y) with field tables (offset, length, PIC, COMP-3) generated from the copybook text; `encode()`/`decode()` produce/consume exact fixed-length bytes |
| `cobol_numeric.py` | runtime | zoned-decimal decode/encode emulating the GnuCOBOL/ASCII conventions above (accept `{`/`}`/`p–y`, emit plain digit or `p–y`; preserve raw bytes for untouched fields), COMP-3 pack/unpack, `rounded()` = `ROUND_HALF_UP` to 2 dp, `Z(8)9.99-` / `Z(8)9` edit-picture formatting, `S9(9)V99` DISPLAY formatting (`+00000000650`) |
| `extract.py` | `CBXFR01C` / STEP010 | BR-1…BR-5; returns RC and SYSOUT lines |
| `post_fees.py` | `XFERFEE` / STEP020 | BR-6…BR-14; `psycopg` (v3) connection from `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` or `OCDB_*`; parameterised SQL identical to §2.9; single commit at end, rollback on failure; RC 8 on any abend |
| `reconcile.py` | `CBXFR03C` / STEP030 | BR-15…BR-17; writes `\n`-terminated, right-stripped lines |
| `gdg.py` | runner | reads/writes `datasets/<base>.gdg` exactly like `runjcl.py` so `(+1)`/`(0)` resolve to the same `G000nV00` names |
| `run_chain.py` | `XFRDAILY`/`XFERFEEP` | runs the three steps in JCL order with the DD→path mapping of §1.3, applies `COND=(4,LT,STEP020)`, writes `sysout/STEPnnn.SYSOUT`, `rc.json` (`{"steps": {...}, "maxcc": n}`), and dumps `CTL_XFER_PARM` / `XFER_FEE_LEDGER` to `db2_after/*.csv` in the same column order/format as `tools/parity/recorder.py`, producing the candidate directory `work/parity/<case>/candidate/{datasets,db2_after,sysout,rc.json}` |

Every function carries a docstring citing the BR numbers it implements.

Datasets: fixed-length binary files, no delimiters; COMP-3 encoded with a `C`/`D` sign nibble;
fillers emitted as `0x00` to match the recorded generations. Extract `XFR-TRAN-AMT` and untouched
account fields are copied as raw bytes so the sign byte round-trips exactly as the COBOL `MOVE`
does.

Parity: `make run-python CASE=<case>` runs `run_chain.py` for one fixture (loading its inputs,
truncating the ledger, resetting the GDG catalog), then `tools/parity/compare.py --chain xferfee
--case <case> --candidate work/parity/<case>/candidate`; `make parity-python` loops all seven and
collects the Markdown reports. The comparator, fixtures and COBOL are not modified. The extra
behaviours in §4.8 will be added as Python unit tests (not fixtures) if the owner confirms Q2/Q3/Q9.

`python/xferfee/README.md` will carry the JCL-step→module, DD→file, copybook-field→dataclass-field
and SQL→query mapping tables.
