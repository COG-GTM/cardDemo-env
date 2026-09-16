"""Build BMS maps, command-level COBOL programs and PCT/PPT tables on KICKS.

Sources live on the host (tools/kicks/minimal, tools/kicks/carddemo) and are
shipped to MVS as in-stream data in the job that compiles them, using the
KICKS-supplied cataloged procedures:

  KIKMAPS  - KIKMG map generator -> IFOX00 assembler -> IEWL into KIKRPL,
             plus a COBOL DSECT copybook written to KICKS.V1R5M0.COBCOPY(map)
  KIKCOBCL - KIKPPCOB pre-processor -> IKFCBL00 (OS/VS COBOL) -> IEWL with
             the KIKCOBGL stub into KICKS.V1R5M0.KIKRPL

KICKS only runs what is in its tables, so a PCT (transaction -> program) and
a PPT (programs and maps) with suffix `CD` are assembled from the shipped
KIKPCT1$/KIKPPT1$ jobs with our entries appended.  KICKS is then started
with `KICKS PCT(CD) PPT(CD)`.
"""
import os
import re
import time

import mvs3270 as m
from kicks_install import JOBCARD, HLQ, VOL, print_member, rejob

SUFFIX = "CD"
PROCLIB = f"{HLQ}.KICKSSYS.V1R5M0.PROCLIB"
USR_SKIKLOAD = f"{HLQ}.KICKS.V1R5M0.SKIKLOAD"
USR_COBCOPY = f"{HLQ}.KICKS.V1R5M0.COBCOPY"
USRSEC_DSN = f"{HLQ}.CARDDEMO.USRSEC"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get("CARDDEMO_REPO", os.path.dirname(os.path.dirname(HERE)))


def instream(text):
    """Return `text` as in-stream data; JCL forbids `//` and `/*` in column 1."""
    bad = [l for l in text.splitlines() if l.startswith("//") or l.startswith("/*")]
    if bad:
        raise ValueError("source cannot be in-stream data: " + bad[0])
    return text.rstrip("\n") + "\n/*\n"


def map_job(mapset, src):
    return (
        JOBCARD.format(name="MAP" + mapset[:5], desc="MAP " + mapset)
        + f"//JOBPROC DD DSN={PROCLIB},DISP=SHR\n"
        + f"//{mapset} EXEC KIKMAPS,MAPNAME={mapset}\n"
        + "//COPY.SYSUT1 DD *\n"
        + instream(src)
    )


def cobol_job(program, src):
    return (
        JOBCARD.format(name="COB" + program[:5], desc="COBOL " + program)
        + f"//JOBPROC DD DSN={PROCLIB},DISP=SHR\n"
        + f"//{program} EXEC KIKCOBCL\n"
        + "//COPY.SYSUT1 DD *\n"
        + instream(src)
        + "//LKED.SYSIN DD *\n"
        + " INCLUDE SKIKLOAD(KIKCOBGL)\n"
        + f" ENTRY {program}\n"
        + f" NAME {program}(R)\n"
        + "/*\n"
    )


def copybook_job(members):
    """IEBUPDTE the given {member: text} copybooks into the user COBCOPY."""
    body = ""
    for name, text in members.items():
        body += f"./ ADD NAME={name},LIST=ALL\n"
        body += "\n".join(l[:72].rstrip() for l in text.splitlines()) + "\n"
    body += "./ ENDUP\n"
    return (
        JOBCARD.format(name="COPYBKS", desc="COPYBOOKS")
        + "//UPD EXEC PGM=IEBUPDTE,PARM=NEW\n"
        + "//SYSPRINT DD SYSOUT=*\n"
        + f"//SYSUT2 DD DSN={USR_COBCOPY},DISP=SHR\n"
        + "//SYSIN DD DATA\n"
        + instream(body)
    )


def upload_copybooks(paths):
    members = {}
    for p in paths:
        with open(p) as fh:
            members[os.path.splitext(os.path.basename(p))[0]] = fh.read()
    out = m.run_job(copybook_job(members), timeout=600)
    m.save("build-copybooks", out)
    return out


def usrsec_job(records):
    """IDCAMS DEFINE of the USRSEC KSDS (as jcl/DUSRSECJ.jcl does) and REPRO
    of the fixtures/carddemo/ebcdic/AWS.M2.CARDDEMO.USRSEC.PS records."""
    return (
        JOBCARD.format(name="USRSEC", desc="USRSEC KSDS")
        + "//DEFINE EXEC PGM=IDCAMS\n"
        + "//SYSPRINT DD SYSOUT=*\n"
        + "//SYSIN DD *\n"
        + f" DELETE {USRSEC_DSN} CLUSTER PURGE\n"
        + " SET MAXCC = 0\n"
        + " DEFINE CLUSTER -\n"
        + f"   (NAME({USRSEC_DSN}) VOLUMES({VOL}) -\n"
        + "    KEYS(8 0) RECORDSIZE(80 80) INDEXED -\n"
        + "    TRACKS(5 5) FREESPACE(10 15) SHAREOPTIONS(2 3) UNIQUE) -\n"
        + f"   DATA (NAME({USRSEC_DSN}.DATA)) -\n"
        + f"   INDEX (NAME({USRSEC_DSN}.INDEX))\n"
        + "/*\n"
        + "//LOAD EXEC PGM=IDCAMS,COND=(0,NE)\n"
        + "//SYSPRINT DD SYSOUT=*\n"
        + "//IN DD *\n"
        + instream("\n".join(records))
        + f"//OUT DD DSN={USRSEC_DSN},DISP=SHR\n"
        + "//SYSIN DD *\n"
        + " REPRO INFILE(IN) OUTFILE(OUT)\n"
        + "/*\n"
    )


def load_usrsec():
    path = os.path.join(REPO, "fixtures", "carddemo", "ebcdic", "AWS.M2.CARDDEMO.USRSEC.PS")
    with open(path, "rb") as fh:
        raw = fh.read()
    records = [raw[i:i + 80].decode("cp037").rstrip() for i in range(0, len(raw), 80)]
    records = [r for r in records if r.strip()]
    out = m.run_job(usrsec_job(records), timeout=600)
    m.save("build-usrsec", out)
    return out


def build_map(mapset, path):
    with open(path) as fh:
        src = fh.read()
    out = m.run_job(map_job(mapset, src), timeout=900, ok_codes=("0000", "0004"))
    m.save(f"build-map-{mapset}", out)
    return out


def build_cobol(program, path):
    with open(path) as fh:
        src = fh.read()
    out = m.run_job(cobol_job(program, src), timeout=900, ok_codes=("0000", "0004"))
    m.save(f"build-cob-{program}", out)
    if "IKF" in out and re.search(r"IKF\d{4}I-[EDC]", out):
        raise RuntimeError(f"{program}: COBOL compile diagnostics:\n"
                           + "\n".join(l for l in out.splitlines() if re.search(r"IKF\d{4}I-[EDC]", l)))
    return out


def table_job(kind, entries):
    """Rebuild the shipped KIKPCT1$/KIKPPT1$ job as suffix SUFFIX plus `entries`.

    kind: "PCT" or "PPT"; entries: list of KIKPCT/KIKPPT operand strings.
    """
    lines = print_member(f"{HLQ}.KICKSSYS.V1R5M0.INSTLIB", f"KIK{kind}1$")
    out = []
    for l in lines:
        if f"KIK{kind} TYPE=FINAL" in l:
            out.append("*")
            out.append("*        CARDDEMO-ENV ENTRIES (tools/kicks)")
            out.append("*")
            etype = "DATASET" if kind == "FCT" else "ENTRY"
            for e in entries:
                out.append(f"         KIK{kind} TYPE={etype},{e}")
        l = l.replace("SUFFIX=1$", f"SUFFIX={SUFFIX}")
        l = re.sub(rf"DSN=\S+\.SKIKLOAD\(KIK{kind}1\$\)", f"DSN={USR_SKIKLOAD}(KIK{kind}{SUFFIX})", l)
        out.append(l)
    return rejob(out, f"KIK{kind}{SUFFIX}", f"{kind} {SUFFIX}")


def build_tables(pct_entries, ppt_entries, fct_entries=None):
    kinds = [("PCT", pct_entries), ("PPT", ppt_entries)]
    if fct_entries is not None:
        kinds.append(("FCT", fct_entries))
    for kind, entries in kinds:
        jcl = table_job(kind, entries)
        m.save(f"table-{kind}", jcl)
        out = m.run_job(jcl, timeout=900)
        m.save(f"build-{kind}", out)


def kicks_start(s, timeout=180, fct=False):
    """From TSO READY: start KICKS with our tables, return the KSGM screen.

    The shipped KICKS clist only ALLOCs its own example files, so user files
    are allocated to their FCT DDNAME here before the clist runs."""
    tables = f"PCT({SUFFIX}) PPT({SUFFIX})"
    if fct:
        m.tso_cmd(s, "FREE FI(USRSEC)", timeout=60)
        m.tso_cmd(s, f"ALLOC FI(USRSEC) DA('{USRSEC_DSN}') SHR", timeout=60)
        tables += f" FCT({SUFFIX})"
    s.clear()
    s.type(f"EXEC '{HLQ}.KICKSSYS.V1R5M0.CLIST(KICKS)' '{tables}'")
    s.enter()
    deadline = time.time() + timeout
    while time.time() < deadline:
        scr = s.ascii()
        if "KSGM for tso user" in scr:
            return scr
        if "NOT CURRENTLY ALLOWED" in scr or "COMMAND" in scr and "NOT FOUND" in scr:
            raise RuntimeError("KICKS did not start:\n" + scr)
        lines = [l for l in scr.splitlines() if l.strip()]
        if lines and lines[-1].strip() == "***":
            s.enter()
        time.sleep(2)
    raise TimeoutError("KICKS KSGM screen not reached:\n" + scr)


def kicks_tran(s, tranid):
    """From KSGM (or any finished transaction): CLEAR, type a tranid, Enter."""
    s.clear()
    time.sleep(1)
    s.type(tranid)
    s.enter()
    time.sleep(2)
    return s.ascii()


MINIMAL_PCT = ["TRANSID=HELO,PROGRAM=HELOPGM"]
MINIMAL_PPT = ["PROGRAM=HELOPGM,PGMLANG=CMDLVL", "PROGRAM=HELOSET,USAGE=MAP"]


def run_minimal(build=True):
    """Deliverable 2: one map + one program of our own, screen-dumped via s3270."""
    m.wait_http()
    if build:
        build_map("HELOSET", os.path.join(HERE, "minimal", "HELOSET.bms"))
        build_cobol("HELOPGM", os.path.join(HERE, "minimal", "HELOPGM.cbl"))
        build_tables(MINIMAL_PCT, MINIMAL_PPT)
    s = m.Screen()
    m.tso_logon(s)
    try:
        m.save("kicks-signon", kicks_start(s))
        m.save("minimal-helo-1-initial", kicks_tran(s, "HELO"))
        s.type("MAINFRAME")
        s.enter()
        time.sleep(2)
        m.save("minimal-helo-2-greeting", s.ascii())
        s.pf(3)
        time.sleep(2)
        m.save("minimal-helo-3-exit", s.ascii())
        kicks_stop(s)
    finally:
        m.tso_logoff(s)
        s.close()


CARDDEMO_COPYBOOKS = ["COCOM01Y", "COTTL01Y", "CSDAT01Y", "CSMSG01Y", "CSUSR01Y"]
CARDDEMO_PCT = MINIMAL_PCT + [
    "TRANSID=CC00,PROGRAM=COSGN00C",
    "TRANSID=CM00,PROGRAM=COMEN01C",
]
CARDDEMO_PPT = MINIMAL_PPT + [
    "PROGRAM=COSGN00C,PGMLANG=CMDLVL",
    "PROGRAM=COSGN00,USAGE=MAP",
    "PROGRAM=COMEN01C,PGMLANG=CMDLVL",
    "PROGRAM=COMEN01,USAGE=MAP",
]
CARDDEMO_FCT = ["DATASET=USRSEC"]


def signon(s, user, pwd):
    """Fill the COSGN0A map: User ID at (19,44), Password at (20,44)."""
    s.cmd("MoveCursor(18,43)")
    s.cmd("EraseEOF()")
    s.type(user)
    s.cmd("MoveCursor(19,43)")
    s.cmd("EraseEOF()")
    s.type(pwd)
    s.enter()
    time.sleep(2)


def menu_option(s, opt):
    """Type into COMEN01's option field (the only unprotected field)."""
    s.cmd("Reset()")
    s.cmd("Home()")
    s.cmd("EraseEOF()")
    s.type(opt)
    s.enter()
    time.sleep(2)


def run_carddemo(build=True, stop=True):
    """Deliverable 3: CardDemo sign-on (COSGN00C) -> menu (COMEN01C) on KICKS."""
    m.wait_http()
    if build:
        upload_copybooks(
            [os.path.join(REPO, "copybook", c + ".cpy") for c in CARDDEMO_COPYBOOKS]
            + [os.path.join(HERE, "carddemo", "COMEN02Y.cpy")]
        )
        load_usrsec()
        build_map("COSGN00", os.path.join(HERE, "carddemo", "COSGN00.bms"))
        build_cobol("COSGN00C", os.path.join(HERE, "carddemo", "COSGN00C.cbl"))
        build_map("COMEN01", os.path.join(HERE, "carddemo", "COMEN01.bms"))
        build_cobol("COMEN01C", os.path.join(HERE, "carddemo", "COMEN01C.cbl"))
        build_tables(CARDDEMO_PCT, CARDDEMO_PPT, CARDDEMO_FCT)
    s = m.Screen()
    m.tso_logon(s)
    try:
        kicks_start(s, fct=True)
        m.save("carddemo-cc00-1-signon", kicks_tran(s, "CC00"))
        s.enter()                      # empty user id -> validation message
        time.sleep(2)
        m.save("carddemo-cc00-2-nouser", s.ascii())
        signon(s, "USER0001", "WRONGPW")
        m.save("carddemo-cc00-3-badpw", s.ascii())
        signon(s, "USER0001", "PASSWORD")
        time.sleep(1)
        m.save("carddemo-cc00-4-signed-on", s.ascii())
        # COMEN01C: pick option 1 (Account View -> COACTVWC, not ported)
        # then a blank option, to exercise the menu's own validation.
        menu_option(s, "1")
        m.save("carddemo-cm00-5-option1", s.ascii())
        menu_option(s, "99")
        m.save("carddemo-cm00-6-badopt", s.ascii())
        if stop:
            s.pf(3)                    # menu -> back to sign-on
            time.sleep(2)
            m.save("carddemo-cm00-7-back-to-signon", s.ascii())
            s.pf(3)                    # sign-on -> "Thank you" and exit
            time.sleep(2)
            m.save("carddemo-cc00-8-exit", s.ascii())
            kicks_stop(s)
    finally:
        if stop:
            m.tso_logoff(s)
        s.close()


def kicks_stop(s):
    """KSSF shuts KICKS down and returns to TSO READY."""
    s.cmd("Reset()")
    s.clear()
    s.cmd("Reset()")
    time.sleep(1)
    s.type("KSSF")
    s.enter()
    for _ in range(30):
        scr = s.ascii()
        if m.at_ready(scr):
            return scr
        lines = [l for l in scr.splitlines() if l.strip()]
        if lines and lines[-1].strip() == "***":
            s.enter()
        time.sleep(2)
    return s.ascii()
