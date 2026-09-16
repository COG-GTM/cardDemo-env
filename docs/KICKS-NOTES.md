# KICKS notes: a genuine 3270 "CICS" path for CardDemo

`tools/kicks/` boots a real IBM MVS 3.8j operating system under the Hercules
System/370 emulator and runs the CardDemo sign-on and main-menu screens on
KICKS for TSO, a CICS look-alike. Everything on this path is public domain or
free-to-use software; nothing is mocked. This document records what was
downloaded, what had to be rewritten to make the two CardDemo programs
compile on a 1970s compiler, what works, what does not, and whether it is
worth showing to a customer next to the GnuCOBOL/PostgreSQL estate in the
rest of this repo.

The batch estate (`docker compose up`, `make run`) is unchanged; the `mvs`
service sits behind a Compose profile.

## What TK5 and KICKS are

- **Hercules** is an open-source System/370, ESA/390 and z/Architecture
  emulator. TK5 ships SDL Hercules 4.9.1.0 prebuilt for Linux x86-64 (linked
  against glibc 2.38, hence the `ubuntu:24.04` base image).
- **MVS 3.8j** is the last release of IBM's MVS that IBM placed in the public
  domain. It is OS/VS2 Release 3.8 from 1981: JES2, TSO, VTAM, VSAM, and the
  OS/VS ("MVT") ANS COBOL compiler `IKFCBL00`. No CICS, IMS, Db2 or MQ were
  ever public domain, so none of them exist here.
- **TK5** ("Turnkey 5", Rob Prins, 2023-) is a ready-to-IPL MVS 3.8j
  distribution: DASD volumes, Hercules configuration, RAKF security, TSO
  users (`HERC01`..`HERC04`), and quality-of-life tooling. It is the current
  successor of the older TK3/TK4- distributions.
- **KICKS for TSO** (Mike Noel, 1.5.0, September 2014) implements the CICS
  command-level API (`EXEC CICS`/`EXEC KICKS ... END-EXEC`), BMS maps, a
  PCT/PPT/FCT table set and a transaction scheduler as a TSO application
  driven from a 3270 session. It is not CICS; it is a re-implementation of
  the subset of the API that a hobbyist can use on MVS 3.8j.

## Licensing

| Component | License | Notes |
|---|---|---|
| Hercules 4.x (SDL) | Q Public License 1.0 | Emulator only; TK5 bundles binaries. |
| MVS 3.8j | Public domain (IBM) | TK5 adds user mods that are also freely redistributable. |
| TK5 packaging | Free to use and redistribute per its README | |
| KICKS for TSO 1.5.0 | Custom free license, quoted below | Not open source; object code may not be modified. |
| CardDemo sources | Apache-2.0 (Amazon) | The rewritten copies under `tools/kicks/carddemo/` keep the header. |

KICKS license (from `kicks-license.txt` inside the download; the whole file
is copied to `/opt/mvs/KICKS-LICENSE.txt` in the image):

> KICKS use is in all cases subject to license, and a variety of licenses are
> available. This license for the 1.5.0 version of KICKS is offered for free
> use subject to the following conditions.
>
> 1. USE. Licensee (hereafter sometimes called you, or your) may install the
> entire and complete original KICKS distribution package on all secure
> computers in your organization, anywhere, for access by anyone you
> authorize to use KICKS on those computers. [...]
>
> 3. WARRANTY. KICKS is licensed without warranty of any kind. [...] KICKS is
> not intended for use in any situation where its failure could cause harm or
> damages of any sort. Failures are in fact certain. [...]
>
> 5. MODIFICATION OF CODE. KICKS object code may not be modified in any way
> except by the application of updates obtained directly from me. You may,
> entirely at your own risk, modify objects distributed in source form as
> long as copyright notices are retained.
>
> 6. REDISTRIBUTION. If you provide KICKS materials to another party, you
> must provide it without cost to that party, and must provide them the
> entire and complete original KICKS distribution package including all
> install files, documentation, and this license. [...]

Because of clause 6 the repository does **not** vendor KICKS or TK5; the
Dockerfile downloads the complete original archives at build time and
verifies them.

## Exact versions downloaded

| Artifact | URL | SHA256 |
|---|---|---|
| TK5 (mvs-tk5.zip, Hercules 4.9.1.0-SDL + MVS 3.8j) | https://www.prince-webdesign.nl/images/downloads/mvs-tk5.zip | `710d002843631322810a276dd42c793fda458548dc64d86e2914a62db7425f84` |
| KICKS for TSO v1r5m0 (kicks-tso-v1r5m0.zip) | http://www.kicksfortso.com/kicks-tso-v1r5m0.zip | `e0df01bee1988c1c73dbb0a49e082e8ae9fda5822a02f8b9c798583565c177f1` |

Checksums were taken from the files fetched on 2026-09-16 and are pinned in
`tools/kicks/Dockerfile` (`TK5_SHA256`, `KICKS_SHA256`); the build fails if
the upstream files change. TK5 "update 5" (`mvstk5-update5.zip`,
`c44fb64c...8c7212`) was downloaded and inspected but is not applied; base
TK5 was sufficient. Only the KICKS `.xmi` and license are extracted from the
KICKS zip (the zip contains oddly-stored entries that `unzip` warns about).

## How to run it

```sh
make mvs-up        # build image (downloads ~600 MB), boot MVS, wait for TSO
make mvs-install   # one-time: RECEIVE the KICKS XMI, run KFIX + load jobs
make mvs-kicks     # log on HERC01, start KICKS, dump the KSGM sign-on screen
make mvs-minimal   # build + run the HELO probe transaction (deliverable 2)
make mvs-carddemo  # build + run CardDemo CC00 sign-on -> CM00 menu
make mvs-3270      # interactive c3270 to localhost:3270
make mvs-down      # orderly MVS shutdown (SIGTERM -> TK5 shutdown script)
```

The KICKS install and all compiled artifacts live on the `mvs-dasd` volume,
so `mvs-install` is a one-time step per volume. Every `mvs3270.py` action
writes plain-text `Ascii()` screen captures into `tools/kicks/out/`; the
captures referenced below are committed as evidence. The Hercules HTTP
console is on http://localhost:8038 (used by the scripts to read the syslog
and to send the shutdown command); the JES2 card reader is on port 3505 and
is how all JCL is submitted.

Boot time, measured from the Hercules console log
(`tools/kicks/out/hercules-ipl-console.txt`): Hercules start 18:08:36, IPL
prompt `IEA101A` 18:08:40, JES2 up 18:08:46, `IST020I VTAM INITIALIZATION
COMPLETE` and `IEF403I TSO - STARTED` 18:09:18 — about **43 seconds** on a
shared 4-vCPU VM. KICKS start-up from `EXEC 'HERC01.KICKSSYS.V1R5M0.CLIST(KICKS)'`
to the KSGM screen is 3-5 s. A compile-and-link of one BMS map plus one
program through the card reader is 20-40 s end to end.

The 3270 driver is `tools/kicks/mvs3270.py` (plain `s3270 -scriptport`
polling `Ascii()`; no expect-style waits, which hang on VTAM). It handles
the TSO logon, `***` pagination, JES2 output retrieval from
`/opt/mvs/prt/prt00e.txt`, and cond-code checking. `kicks_install.py` does
the KICKS install (upload XMI via the reader, `RECV370`, `KFIX`, the
`LOADMUR/LOADTAC/LOADSDB/LODINTRA/LODTEMP` jobs). `kicks_build.py` builds
maps (`KIKMAPS` proc), programs (`KIKCOBCL` proc: KICKS preprocessor ->
`IKFCBL00` -> `IEWL`), a custom `CD`-suffixed PCT/PPT/FCT, the `USRSEC`
VSAM KSDS, and drives the transactions.

## Deliverable 2: a real BMS screen (HELO)

`tools/kicks/minimal/HELOSET.bms` (one mapset, one map) and
`tools/kicks/minimal/HELOPGM.cbl` (one command-level program, transaction
`HELO`) were written from scratch for this compiler. They compile with
`RC=0`, are registered in `KIKPCTCD`/`KIKPPTCD`, and run under
`KICKS PCT(CD) PPT(CD)`. Evidence:

- `out/minimal-helo-1-initial.txt` — map sent with `ERASE`, cursor in the
  NAME field, `ASKTIME/FORMATTIME` time in the header.
- `out/minimal-helo-2-greeting.txt` — after typing `MAINFRAME` + ENTER:
  `HELLO, MAINFRAME - SENT BY A REAL BMS MAP ON KICKS`.
- `out/minimal-helo-3-exit.txt` — PF3 -> `SEND TEXT` goodbye -> `RETURN`.

Lessons that carried into the CardDemo port: the generated `...I`/`...O`
map structures REDEFINE each other, so input fields must be copied to
WORKING-STORAGE before `MOVE LOW-VALUES TO mapO`; `VALUE` literals longer
than the PIC are only a C-level warning but silently truncate; KIKMAPS wants
`INITIAL=` literals to fit on one card.

## Deliverable 3: CardDemo sign-on and menu on KICKS

Result: **`COSGN00C` + `COSGN00.bms` and `COMEN01C` + `COMEN01.bms` both
compile, link and run**, with the real `USRSEC` fixture loaded into a VSAM
KSDS. The path exercised (all under `tools/kicks/out/`):

| Capture | What it shows |
|---|---|
| `carddemo-cc00-1-signon.txt` | CC00 initial screen, title/date/time/AppID/SysID populated. |
| `carddemo-cc00-2-nouser.txt` | ENTER with empty fields -> `Please enter User ID ...`. |
| `carddemo-cc00-3-badpw.txt` | `USER0001` + wrong password -> `Wrong Password. Try again ...` (record was read from the KSDS and compared). |
| `carddemo-cc00-4-signed-on.txt` | Valid credentials -> `XCTL` to `COMEN01C` -> main menu with all 11 options rendered from `COMEN02Y`. |
| `carddemo-cm00-5-option1.txt` | Option `1` -> right-justified to `01`, `XCTL PROGRAM('COACTVWC')` fails with `PGMIDERR` -> `This option Account View is not installed...`. |
| `carddemo-cm00-6-badopt.txt` | Option `99` -> `Please enter a valid option number...`. |
| `carddemo-cm00-7-back-to-signon.txt` | PF3 on the menu -> `XCTL` back to `COSGN00C`. |
| `carddemo-cc00-8-exit.txt` | PF3 on sign-on -> `Thank you for using CardDemo application...`. |

Per the brief, the port stops at the menu. No account/card/transaction
program was attempted; option 1 deliberately shows the "not installed"
branch so the boundary is visible on screen.

### Toolchain facts that drove the rewrites

- The compiler is IBM OS/VS ("MVT") ANS COBOL, roughly ANS-68 with some
  ANS-74 features. Verified rejections, with the diagnostic seen:
  - `STRING`/`UNSTRING`/`INSPECT` do not exist at all
    (`IKF3001I-E STRING NOT DEFINED`, `IKF4003I-E EXPECTING NEW STATEMENT`).
  - `LENGTH OF identifier` is not a thing
    (`IKF3006I-E LENGTH NOT DEFINED AS PART OF CARDDEMO-COMMAREA`).
  - No `EVALUATE`, `END-IF`, `END-PERFORM`, `CONTINUE`, `INITIALIZE`,
    inline `PERFORM`, `FUNCTION ...`, reference modification `X(1:n)`, or
    abbreviated conditions with figurative constants (`= SPACE OR LOW-VALUE`
    failed; `= SPACE OR X = LOW-VALUE` compiles).
  - `OCCURS n TIMES` under a `REDEFINES` whose subject is longer than the
    object is a C-level diagnostic that makes the step end `RC=8`, which
    the KIKCOBCL proc treats as a failed compile.
- The KICKS preprocessor accepts `EXEC CICS` as a synonym for `EXEC KICKS`,
  but the DFH* copybooks are named `KIKAID`/`KIKBMSCA` and the AID/attribute
  symbols are `KIKENTER`, `KIKPF3`, `KIKRED`, `KIKGREEN`, and so on.
- KICKS BMS (`KIKMG`) rejects `LENGTH=0` fields (`?LENGTH in KIKMDF not
  numeric`), `JUSTIFY=(RIGHT,ZERO)` (`unknown argument`), and the macro
  continuation style used upstream (`-` in column 72 with the literal split
  across cards); it wants `X` in column 72 and whole literals on one card.
  The `&&SYSPARM` escaping in upstream is for CICS' `DFHMAPS` proc; KIKMAPS
  wants `&SYSPARM`. Generated `mapO` structures contain only the `A` and
  `O` sub-fields; the extended `C`/`P`/`H`/`V` attribute bytes are only
  addressable through the `mapI` structure.
- KICKS has no `INQUIRE PROGRAM`, no `EXEC CICS ASSIGN APPLID` (it does
  have `ASSIGN SYSID`, returning `TK5R`; the `AppID` field is set to the
  constant `KICKS`), and requires a `COMMAREA` on `XCTL` to be addressable
  storage passed with an explicit `LENGTH`.
- VSAM KSDS access works: `READ DATASET ... RIDFLD KEYLENGTH RESP RESP2`
  against an `IDCAMS DEFINE CLUSTER ... INDEXED UNIQUE` cluster loaded with
  `REPRO` from the EBCDIC fixture (`kicks_build.load_usrsec`). The FCT entry
  is `KIKFCT TYPE=DATASET,DATASET=USRSEC` and the KICKS start-up CLIST needs
  a matching `ALLOC FI(USRSEC) DA(...) SHR`. On MVS 3.8j the dataset had to
  be `UNIQUE` (`IDC3025I INSUFFICIENT SUBALLOCATION DATA SPACE` otherwise).

### File-by-file rewrites (`tools/kicks/carddemo/`, originals untouched)

**`COSGN00.bms`** (from `bms/COSGN00.bms`)
- Continuation character `-` -> `X`; `TYPE=&&SYSPARM` -> `TYPE=&SYSPARM`.
- Two literals that upstream continued across cards (`Main-`/`frame`,
  `ENTE-`/`R:`) became two adjacent protected fields each.
- Decorative `LENGTH=0` fields removed.
- Trailing lines after `DFHMSD TYPE=FINAL`/`END` dropped.

**`COSGN00C.cbl`** (from `cobol/COSGN00C.cbl`)
- `EVALUATE EIBAID` -> nested `IF ... ELSE` on `KIKENTER`/`KIKPF3`; all
  `END-IF`/`CONTINUE` removed; every sentence period-terminated.
- `FUNCTION UPPER-CASE(...)` -> `TRANSFORM ... FROM 'a..z' TO 'A..Z'`.
- `FUNCTION CURRENT-DATE` -> `EXEC KICKS ASKTIME ABSTIME` +
  `FORMATTIME MMDDYY(...) TIME(...) DATESEP TIMESEP`.
- `LENGTH OF CARDDEMO-COMMAREA` / `LENGTH OF WS-MESSAGE` -> explicit
  `WS-CA-LEN` (160) / `WS-MSG-LEN` (80) COMP fields.
- `EXEC CICS ASSIGN APPLID(...)` dropped (not in KICKS); `ASSIGN SYSID`
  kept.
- `READ DATASET` gained `LENGTH(WS-SEC-LEN)` and `KEYLENGTH(WS-KEY-LEN)`.
- `RETURN TRANSID(...) COMMAREA(...)` gained an explicit `LENGTH`.
- Copybooks renamed `DFHAID` -> `KIKAID`, `DFHBMSCA` -> `KIKBMSCA`, and the
  `DFHRED`/`DFHGREEN` attribute symbols -> `KIKRED`/`KIKGREEN`.
- `EXEC CICS` -> `EXEC KICKS` for readability (KICKS accepts both).

**`COMEN01.bms`** (from `bms/COMEN01.bms`)
- Same continuation/`&SYSPARM`/trailing-lines treatment as `COSGN00.bms`.
- `OPTION` field: `JUSTIFY=(RIGHT,ZERO)` removed (right-justification is
  done in the program instead).
- One `LENGTH=0` field changed to `LENGTH=1`.

**`COMEN01C.cbl`** (from `cobol/COMEN01C.cbl`)
- `EVALUATE` -> nested `IF`; inline `PERFORM VARYING ... END-PERFORM` ->
  out-of-line `PERFORM BUILD-ONE-OPTION VARYING WS-IDX ...`; the 12
  `OPTN001O..OPTN012O` moves are an `IF WS-IDX = n` ladder because there is
  no reference modification or `OCCURS` on the generated map fields.
- `OPTIONI(1:WS-IDX)` right-justify + `INSPECT ... REPLACING` -> a 2-byte
  `REDEFINES` with explicit `IF`s per byte.
- `STRING ... DELIMITED BY` (three places) -> `MOVE`s into fixed-width
  group items (`WS-MENU-OPT-TXT`, `WS-OPT-MSG`). Side effect: the option
  name in the "coming soon"/"not installed" message is padded to 35 chars
  instead of trimmed (visible in `carddemo-cm00-5-option1.txt`).
- `EXEC CICS INQUIRE PROGRAM` -> attempt the `XCTL` with `RESP` and treat a
  non-zero response as "not installed".
- `FUNCTION CURRENT-DATE` -> `ASKTIME`/`FORMATTIME`; `LENGTH OF` ->
  explicit `WS-CA-LEN`.
- `ERRMSGC OF COMEN1AO` -> `ERRMSGC OF COMEN1AI` (only the `I` structure
  carries the colour byte in KICKS-generated symbolic maps).
- The `XCTL COMMAREA` is `DFHCOMMAREA` (LINKAGE) rather than the
  WORKING-STORAGE copy.

**`COMEN02Y.cpy`** (from `copybook/COMEN02Y.cpy`)
- Upstream declares `CDEMO-MENU-OPT OCCURS 12 TIMES` over 11 initialised
  entries (a latent upstream bug: the 12th slot is uninitialised storage
  past the REDEFINES object). Added a 12th `DUMMY` entry so the REDEFINES
  lengths match and the compile returns `RC=4` instead of `RC=8`. The other
  five CardDemo copybooks (`COCOM01Y`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`,
  `CSUSR01Y`) are uploaded from `copybook/` unmodified and compile clean.

### How much of CardDemo online would run here

Two of 27 programs were ported; each needed roughly 30-60 lines of hand
changes and one to three compile iterations. Extrapolating honestly:

- Every remaining `CO*` program uses `EVALUATE`, `END-IF`, inline `PERFORM`,
  `STRING`/`INSPECT`, `FUNCTION`, reference modification and `LENGTH OF`
  throughout; each would be a full hand rewrite, not a mechanical one.
- Screen programs that only `READ`/`STARTBR`/`READNEXT` VSAM KSDS files
  (`COACTVWC`, `COCRDLIC`, `COCRDSLC`, `COTRN00C`, `COTRN01C`, `COUSR*`)
  are feasible: KICKS supports browse and update on KSDS via the FCT.
- `COBIL00C`/`COTRN02C` (`WRITE`/`REWRITE`, `SYNCPOINT`) would work
  functionally but with no real recovery; KICKS has no logging or unit of
  work beyond VSAM's own.
- Nothing that touches Db2 (`db2/`), MQ (`mq/`), IMS or the authorization
  path (`app-authorization-ims-db2-mq`) can run: none of those products
  exist for MVS 3.8j. The batch programs' `EXEC SQL` would have no
  precompiler.
- The `COBOL-85` -> `OS/VS COBOL` rewrite is the dominant cost and is
  throwaway work; no customer runs OS/VS COBOL in production today.

## What does not work / known limitations

- **Db2, MQ, IMS, CICS proper**: not available, not emulatable, never will be
  on MVS 3.8j.
- **COBOL-85 and later**: unsupported; see the rewrite list. GnuCOBOL cannot
  target MVS either, so there is no way to run the upstream sources
  unchanged.
- **KICKS is CICS-like, not CICS**: no `INQUIRE`/`SET`, no `APPLID`, no
  `DFHCOMMAREA` addressing via `ADDRESS`, no channels/containers, no web
  or TCP/IP services, no transaction isolation or journaling, single-user
  per TSO session (each 3270 user runs their own KICKS region).
- **Terminal colours**: the maps carry `COLOR=` and `HILIGHT=`; s3270
  `Ascii()` dumps do not show them. Connect with `make mvs-3270` (c3270,
  model 3279-2) to see colour.
- **Security**: TK5 ships with well-known TSO passwords and the container
  exposes TN3270 unauthenticated; this is a demo box only.
- **Image size and build time**: ~1.1 GB image, 3-6 minutes to build
  (download-bound); the KICKS install is another ~4 minutes of JCL.
- **Fragility**: the driver waits on screen text. A stuck TSO session
  (`IKJ606I ... IN USE`) is cleared by `mvs3270.py herc '/c u=HERC01'`.
- TK5 Update 5 is not applied.

## Recommendation for a customer demo

Show it, but as a five-minute "this is what the estate *came from*"
opener, not as the modernization target:

- **Pros**: it is a genuine MVS with a genuine 3270 data stream. The
  CardDemo sign-on and menu look exactly like the customer's own CICS
  screens, which lands emotionally in a way a GnuCOBOL console never does.
  The RC=8 compile listings and the ANS-68 rewrites are a concrete,
  screen-visible illustration of why COBOL-85 estates cannot simply be
  "lifted" onto free tooling — i.e. why modernization is hard.
- **Cons**: everything past the menu is throwaway hand-porting to a
  compiler nobody uses; there is no Db2/MQ/IMS story at all; boot + install
  is minutes and network-bound; the KICKS license forbids modifying or
  partially redistributing it, so it cannot become part of a product.

The GnuCOBOL/PostgreSQL estate remains the right place to demonstrate
modernization work (parity harness, chain graph, dead-code split, real
SQL). Position the KICKS box as the "before" picture and the batch estate
as the "after", and keep the incremental-modernization message: inventory
the estate, port one component, prove parity or document exactly what
breaks — which is what `tools/kicks/carddemo/` does for two programs.
