"""One-time KICKS for TSO 1.5.0 install into TK5, driven from the host side.

Follows the "Turnkey MVS" section of the KICKS User's Guide (Installation):
  1. feed the XMI file through card reader 10C and copy it to a data set (GETXMI)
  2. RECV370 the XMI into HERC01.KICKS.V1R5M0.BIGPDS (RCVKICKS)
  3. run BIGPDS(V1R5M0), the batch job that RECEIVEs every KICKS data set
  4. KFIX customisation clist, then the LOADMUR/LOADTAC/LOADSDB/LODINTRA/LODTEMP
     VSAM define+load jobs (volume PUB002 -> TSO002, the TK5 user volume)
Every step is a batch job or TSO command so the whole thing is re-runnable
(scratch-and-recreate).
"""
import re
import time

import mvs3270 as m

HLQ = m.USER
VOL = "TSO002"
XMI_ON_HOST = "jcl/kicks.xmi"  # relative to the Hercules cwd /opt/mvs

# TK5 runs RAKF: a job without USER= executes as PROD and may not create
# HERC01.* data sets, so every job card carries the TSO user's credentials.
JOBCARD = (
    "//{name} JOB (KICKS),'{desc}',CLASS=A,MSGCLASS=A,\n"
    f"// MSGLEVEL=(1,1),REGION=4096K,USER={HLQ},PASSWORD={m.PASSWORD}\n"
)

GETXMI = JOBCARD.format(name="GETXMI", desc="LOAD XMI") + f"""//SCRATCH EXEC PGM=IEFBR14
//SYSUT2 DD DSN={HLQ}.KICKS.V1R5M0.XMI,DISP=(MOD,DELETE),
// UNIT=SYSDA,SPACE=(TRK,(0))
//LOAD EXEC PGM=IEBGENER
//SYSPRINT DD SYSOUT=*
//SYSIN DD DUMMY,DCB=BLKSIZE=80
//SYSUT1 DD UNIT=10C,DISP=OLD,DCB=(RECFM=FB,LRECL=80,BLKSIZE=3200)
//SYSUT2 DD DSN={HLQ}.KICKS.V1R5M0.XMI,DISP=(,CATLG),
// DCB=(DSORG=PS,RECFM=FB,LRECL=80,BLKSIZE=3200),
// UNIT=SYSDA,VOL=SER={VOL},SPACE=(TRK,(225,15),RLSE)
"""

RCVKICKS = JOBCARD.format(name="RCVKICKS", desc="RECV370") + f"""//SCRATCH EXEC PGM=IEFBR14
//SYSUT2 DD DSN={HLQ}.KICKS.V1R5M0.BIGPDS,DISP=(MOD,DELETE),
// UNIT=SYSDA,SPACE=(TRK,(0))
//RECV370 EXEC PGM=RECV370
//RECVLOG DD SYSOUT=*
//XMITIN DD DSN={HLQ}.KICKS.V1R5M0.XMI,DISP=SHR
//SYSPRINT DD SYSOUT=*
//SYSUT1 DD DSN=&&SYSUT1,UNIT=SYSDA,SPACE=(TRK,(300,60)),
// DISP=(NEW,DELETE,DELETE)
//SYSUT2 DD DSN={HLQ}.KICKS.V1R5M0.BIGPDS,UNIT=SYSDA,VOL=SER={VOL},
// SPACE=(TRK,(300,60,20)),DISP=(NEW,CATLG,DELETE)
//SYSIN DD DUMMY
"""


def print_member(dsn, member):
    """Print a PDS member to SYSOUT and return its lines.

    SYSUT2 is the only user SYSOUT of the job, so JES2 prints it last: the
    member is whatever sits between the final IEF376I job-stop message and the
    trailing JES2 banner.
    """
    jcl = JOBCARD.format(name="PRTMBR", desc="PRINT " + member) + f"""//PRT EXEC PGM=IEBGENER
//SYSPRINT DD DUMMY
//SYSIN DD DUMMY
//SYSUT1 DD DSN={dsn}({member}),DISP=SHR
//SYSUT2 DD SYSOUT=*
"""
    out = m.run_job(jcl)
    body = out.rsplit("IEF376I", 1)[1].split("\n", 1)[1]
    body = body.split("****A   END", 1)[0]
    lines = [l[:80].rstrip() for l in body.split("\n") if l.strip()]
    # drop the JES2 trailer banner (block letters) if the split left any of it
    while lines and not lines[-1].startswith("//") and lines[-1].startswith(" "):
        lines.pop()
    return lines


def rejob(lines, name, desc):
    """Replace the JOB card of KICKS-supplied JCL with our RAKF-aware one."""
    i = next(i for i, l in enumerate(lines) if " JOB " in l)
    return JOBCARD.format(name=name, desc=desc) + "\n".join(lines[i + 1:]) + "\n"


def step_getxmi():
    m.herc_command(f"devinit 010C {XMI_ON_HOST} ebcdic")
    time.sleep(2)
    m.run_job(GETXMI, timeout=900)


def step_recv370():
    m.run_job(RCVKICKS, timeout=900)


def step_dump_v1r5m0():
    lines = print_member(f"{HLQ}.KICKS.V1R5M0.BIGPDS", "V1R5M0")
    m.save("bigpds-v1r5m0-jcl", "\n".join(lines))
    return lines


def step_recv_all():
    """BIGPDS(V1R5M0): RECV370 every KICKS library out of BIGPDS."""
    lines = step_dump_v1r5m0()
    m.run_job(rejob(lines, "RCVKICK2", "RECV ALL"), timeout=1800)


def step_kfix():
    """KFIX: interactive customisation clist. It asks which HLQ to install
    under (default 1 = &SYSUID = HERC01) and rewrites the shipped JCL/CLISTs
    with PDSUPDTE (K.S. -> HERC01.KICKSSYS., K.U. -> HERC01.KICKS., TCP 2$)."""
    s = m.Screen()
    m.tso_logon(s)
    try:
        s.clear()
        s.type(f"EXEC '{HLQ}.KICKSSYS.V1R5M0.CLIST(KFIX)'")
        s.enter()
        scr = s.wait_for("TYPE YES TO CONTINUE", timeout=120)
        if f"WILL BE 1  {HLQ}" not in scr:
            raise RuntimeError("KFIX offered an unexpected HLQ:\n" + scr)
        s.type("YES")
        s.enter()
        text = m.tso_pages(s, timeout=600, what="KFIX")
        m.save("kfix", text)
        if "DONE!" not in text:
            raise RuntimeError("KFIX did not report DONE!:\n" + text[-3000:])
    finally:
        m.tso_logoff(s)
        s.close()


def load_job(lib, member):
    lines = print_member(f"{HLQ}.{lib}.V1R5M0.INSTLIB", member)
    m.save(f"instlib-{member}", "\n".join(lines))
    jcl = rejob(lines, member[:8], member)
    jcl = jcl.replace("PUB002", VOL)
    m.run_job(jcl, timeout=1200)


def step_loadmur():
    load_job("KICKS", "LOADMUR")


def step_loadtac():
    load_job("KICKS", "LOADTAC")


def step_loadsdb():
    load_job("KICKS", "LOADSDB")


def step_lodintra():
    load_job("KICKSSYS", "LODINTRA")


def step_lodtemp():
    load_job("KICKSSYS", "LODTEMP")


STEPS = ["getxmi", "recv370", "recv_all", "kfix",
         "loadmur", "loadtac", "loadsdb", "lodintra", "lodtemp"]


def run(steps=None):
    m.wait_http()
    for name in steps or STEPS:
        print(f"=== step {name} ===", flush=True)
        globals()["step_" + name]()
