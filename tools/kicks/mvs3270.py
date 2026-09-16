#!/usr/bin/env python3
"""Scripted 3270 driver for the TK5 / KICKS container.

Wraps s3270 so the MVS boot, KICKS install and screen captures are repeatable:

    mvs3270.py wait-ipl                 block until MVS finished IPL (TSO up)
    mvs3270.py install-kicks            one-time KICKS install (re-runnable,
                                        scratch-and-recreate)
    mvs3270.py kicks [--tran KDEM]      logon, start KICKS, optionally run a
                                        transaction, dump the screens
    mvs3270.py minimal [nobuild]        build + run the HELO BMS probe
    mvs3270.py carddemo [nobuild]       build + run CardDemo CC00 -> CM00
    mvs3270.py submit FILE [...]        push JCL into the 3505 card reader
    mvs3270.py screen                   connect and dump the current screen

Screens are dumped as plain-text `Ascii()` captures under --out (default
/opt/kicks/out) so they can be committed or attached as evidence.
"""
import argparse
import http.client
import os
import re
import socket
import subprocess
import sys
import time
import urllib.parse

HOST = os.environ.get("MVS_HOST", "127.0.0.1")
PORT_3270 = int(os.environ.get("MVS_3270_PORT", "3270"))
PORT_RDR = int(os.environ.get("MVS_RDR_PORT", "3505"))
PORT_HTTP = int(os.environ.get("MVS_HTTP_PORT", "8038"))
USER = os.environ.get("MVS_USER", "HERC01")
PASSWORD = os.environ.get("MVS_PASSWORD", "CUL8TR")
OUT = os.environ.get("MVS_OUT", "/opt/kicks/out")
PRT = os.environ.get("MVS_PRT", "/opt/mvs/prt/prt00e.txt")


class Screen:
    """Thin wrapper over an s3270 child process (x3270 scripting protocol)."""

    def __init__(self, lu=None):
        target = f"{HOST}:{PORT_3270}"
        if lu:
            target = f"{lu}@{target}"
        # s3270 only reliably answers the scripting protocol over -scriptport
        # when it has no tty, so talk to it over a loopback socket.
        self.port = 4270 + os.getpid() % 1000
        self.proc = subprocess.Popen(
            ["s3270", "-model", "3279-2", "-scriptport", str(self.port), target],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        try:
            for _ in range(50):
                try:
                    self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=60)
                    break
                except OSError:
                    time.sleep(0.2)
            else:
                raise RuntimeError("s3270 scriptport never opened")
            for _ in range(60):
                if "connected-3270" in " ".join(self.cmd("Query(ConnectionState)")):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("no 3270 session")
            time.sleep(1)
            if "no available 3270 device" in self.ascii():
                raise RuntimeError("all VTAM terminals busy; kill stale s3270 clients")
            # Hercules paints its logo and locks the keyboard; a first Enter makes
            # VTAM answer (INPUT NOT RECOGNIZED) and unlock the USS screen.
            self.cmd("Enter()")
            time.sleep(1)
            self.cmd("Wait(10,Unlock)")
        except Exception:
            if getattr(self, "sock", None):
                self.sock.close()
            self.proc.kill()
            self.proc.wait()
            raise

    def cmd(self, action):
        self.sock.sendall((action + "\n").encode())
        buf = b""
        while not (buf.endswith(b"\nok\n") or buf.endswith(b"\nerror\n")):
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("s3270 exited")
            buf += chunk
        lines = buf.decode("latin-1").split("\n")
        data = [l[6:] for l in lines if l.startswith("data: ")]
        if lines[-2] == "error":
            raise RuntimeError(f"s3270 {action}: {data}")
        return data

    def ascii(self):
        return "\n".join(self.cmd("Ascii()"))

    def text(self):
        return self.ascii()

    def wait_for(self, pattern, timeout=120, poll=1.0):
        deadline = time.time() + timeout
        rx = re.compile(pattern)
        while time.time() < deadline:
            scr = self.ascii()
            if rx.search(scr):
                return scr
            time.sleep(poll)
        raise TimeoutError(f"'{pattern}' not seen; last screen:\n{scr}")

    def type(self, text):
        try:
            self.cmd("Wait(10,Unlock)")
        except RuntimeError:
            self.cmd("Reset()")
        self.cmd(f'String("{text}")')

    def enter(self):
        self.cmd("Enter()")
        self.cmd("Wait(5,Output)")

    def clear(self):
        self.cmd("Clear()")
        try:
            self.cmd("Wait(3,Output)")
        except RuntimeError:
            pass  # TSO answers CLEAR with an empty screen and no data

    def pf(self, n):
        self.cmd(f"PF({n})")
        self.cmd("Wait(5,Output)")

    def key(self, name):
        self.cmd(name)

    def move(self, row, col):
        self.cmd(f"MoveCursor({row},{col})")

    def close(self):
        try:
            self.sock.sendall(b"Quit()\n")
        except OSError:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def save(name, text):
    if PASSWORD:
        text = text.replace(PASSWORD, "********")
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".txt")
    with open(path, "w") as fh:
        fh.write(text + "\n")
    print(f"--- {name} ---\n{text}\n--- saved {path}", flush=True)
    return path


def herc_command(command):
    conn = http.client.HTTPConnection(HOST, PORT_HTTP, timeout=10)
    conn.request("GET", "/cgi-bin/tasks/syslog?" + urllib.parse.urlencode({"command": command}))
    resp = conn.getresponse().read().decode("latin-1")
    conn.close()
    return resp


def herc_syslog():
    conn = http.client.HTTPConnection(HOST, PORT_HTTP, timeout=10)
    conn.request("GET", "/cgi-bin/tasks/syslog?msgcount=400")
    body = conn.getresponse().read().decode("latin-1")
    conn.close()
    body = re.sub(r"<[^>]+>", "", body)
    return body


def wait_http(timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            herc_syslog()
            return
        except OSError:
            time.sleep(2)
    raise TimeoutError("Hercules HTTP console never came up")


def wait_ipl(timeout=600):
    """IPL is complete once VTAM is up and the TSO started task is running."""
    wait_http(timeout)
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = herc_syslog()
        if "IST020I" in log and "IEF403I TSO - STARTED" in log:
            time.sleep(5)
            s = Screen()
            scr = s.ascii()
            s.close()
            return scr
        time.sleep(5)
    raise TimeoutError("MVS did not finish IPL; last console:\n" + herc_syslog()[-3000:])


def submit_text(jcl):
    """Feed JCL text into the JES2 socket card reader (3505 on port 3505)."""
    data = jcl.encode("ascii")
    if not data.endswith(b"\n"):
        data += b"\n"
    with socket.create_connection((HOST, PORT_RDR), timeout=30) as sock:
        sock.sendall(data)


def submit(paths, wait_secs=2):
    for path in paths:
        with open(path) as fh:
            submit_text(fh.read())
        print(f"submitted {path}", flush=True)
        time.sleep(wait_secs)


def prt_read():
    """Everything JES2 printed so far (job output goes to PRINTER1 = 00E)."""
    if not os.path.exists(PRT):
        return ""
    with open(PRT, "rb") as fh:
        return fh.read().decode("latin-1").replace("\r", "")


def run_job(jcl, timeout=600, ok_codes=("0000",), name=None):
    """Submit a job, wait for it to print, return its output; raise on bad RC.

    JES2 prints the job log after the job ends, so job completion is detected
    from the printer file rather than the console.
    """
    name = name or re.match(r"//(\S+)\s+JOB", jcl).group(1)
    before = len(prt_read())
    submit_text(jcl)
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = prt_read()[before:]
        if re.search(rf"\$HASP395 {name}\s+ENDED|IEF45[23]I {name}\b", out):
            time.sleep(2)
            out = prt_read()[before:]
            break
        time.sleep(3)
    else:
        raise TimeoutError(f"job {name} did not end within {timeout}s")
    codes = re.findall(r"COND CODE (\d{4})", out)
    bad = [c for c in codes if c not in ok_codes]
    if bad or "JCL ERROR" in out or re.search(rf"IEF45[023]I {name}\b|IEF472I {name}\b|COMPLETION CODE - SYSTEM", out):
        save(f"failed-{name}", out)
        tail = out[-4000:]
        if PASSWORD:
            tail = tail.replace(PASSWORD, "********")
        raise RuntimeError(f"job {name} failed (cond codes {codes}):\n{tail}")
    print(f"job {name} ended, cond codes {codes}", flush=True)
    return out


def tso_logon(s):
    """Hercules logo (VTAM USS screen) -> TSO READY."""
    for attempt in range(3):
        s.type(f"logon {USER}")
        s.enter()
        scr = s.wait_for("PASSWORD|IN USE", timeout=30)
        if "PASSWORD" in scr:
            break
        # a previous driver run left the user logged on: cancel it from the
        # MVS console and retry
        herc_command(f"/c u={USER}")
        time.sleep(3)
        herc_command(f"/force u={USER}")
        time.sleep(8)
    else:
        raise RuntimeError(f"{USER} stays in use")
    s.type(PASSWORD)
    s.enter()
    scr = s.wait_for(r"READY|\*\*\*|Option +===>", timeout=180)
    # TK5 logs HERC01 straight into ISPF; leave it so we sit at TSO READY.
    for _ in range(15):
        if at_ready(scr):
            return scr
        if re.search(r"Option +===>", scr):
            s.type("X")
            s.enter()
        elif "Process option" in scr or "Disposition" in scr:
            s.type("3")  # delete log/list data sets without printing
            s.enter()
        else:
            s.enter()
        time.sleep(2)
        scr = s.ascii()
    raise RuntimeError(f"TSO READY not reached; last screen:\n{scr}")


def at_ready(scr):
    lines = [l for l in scr.splitlines() if l.strip()]
    return bool(lines) and lines[-1].strip() == "READY"


def tso_logoff(s):
    s.type("logoff")
    s.enter()
    time.sleep(2)


def tso_cmd(s, command, pattern="READY", timeout=180):
    """Run one TSO line-mode command; return the concatenated output pages.

    The screen is cleared first so the command always starts on a fresh page;
    TSO pauses long output with a trailing `***`, answered with Enter.
    """
    s.clear()
    s.type(command)
    s.enter()
    return tso_pages(s, pattern, timeout, what=command)


def tso_pages(s, pattern="READY", timeout=180, what=""):
    """Page through TSO output (Enter on `***`) until READY / `pattern`."""
    pages, last = [], None
    deadline = time.time() + timeout
    while time.time() < deadline:
        scr = s.ascii()
        lines = [l for l in scr.splitlines() if l.strip()]
        if lines and lines[-1].strip() == "***":
            if scr != last:
                pages.append(scr)
                last = scr
                s.enter()  # more output follows
        elif pattern == "READY" and at_ready(scr):
            pages.append(scr)
            return "\n".join(pages)
        elif pattern != "READY" and re.search(pattern, scr):
            pages.append(scr)
            return "\n".join(pages)
        time.sleep(1)
    raise TimeoutError(f"TSO '{what}' did not return; last screen:\n{scr}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["wait-ipl", "screen", "submit", "logon", "tso", "install-kicks", "kicks", "minimal", "carddemo", "build", "herc", "raw"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--tran", default=None)
    ap.add_argument("--name", default="screen")
    ap.add_argument("--timeout", type=int, default=600)
    a = ap.parse_args()

    if a.action == "wait-ipl":
        scr = wait_ipl(a.timeout)
        save("vtam-logo", scr)
        save("hercules-console", herc_syslog())
    elif a.action == "screen":
        s = Screen()
        time.sleep(2)
        save(a.name, s.ascii())
        s.close()
    elif a.action == "submit":
        submit(a.args)
    elif a.action == "raw":
        # debugging aid: mvs3270.py raw 'String("logon herc01")' 'Enter()' 'Ascii()'
        s = Screen()
        for c in a.args:
            if c == "sleep":
                time.sleep(2)
                continue
            out = s.cmd(c)
            if out:
                print("\n".join(out))
        s.close()
    elif a.action == "herc":
        print(herc_command(" ".join(a.args)))
        time.sleep(1)
        print(herc_syslog()[-3000:])
    elif a.action == "logon":
        s = Screen()
        save("tso-ready", tso_logon(s))
        tso_logoff(s)
        s.close()
    elif a.action == "tso":
        s = Screen()
        tso_logon(s)
        try:
            for i, c in enumerate(a.args):
                save(f"tso-{i}", tso_cmd(s, c))
        finally:
            tso_logoff(s)
            s.close()
    elif a.action == "install-kicks":
        import kicks_install
        kicks_install.run(a.args)
    elif a.action == "minimal":
        import kicks_build
        kicks_build.run_minimal(build="nobuild" not in a.args)
    elif a.action == "carddemo":
        import kicks_build
        kicks_build.run_carddemo(build="nobuild" not in a.args)
    elif a.action == "build":
        # build map|cobol NAME PATH  |  build tables
        import kicks_build
        if a.args[0] == "map":
            kicks_build.build_map(a.args[1], a.args[2])
        elif a.args[0] == "cobol":
            kicks_build.build_cobol(a.args[1], a.args[2])
        elif a.args[0] == "tables":
            kicks_build.build_tables(kicks_build.CARDDEMO_PCT, kicks_build.CARDDEMO_PPT,
                                     kicks_build.CARDDEMO_FCT)
        elif a.args[0] == "copybooks":
            kicks_build.upload_copybooks(a.args[1:])
        elif a.args[0] == "usrsec":
            kicks_build.load_usrsec()
    elif a.action == "kicks":
        s = Screen()
        tso_logon(s)
        s.clear()
        s.type(f"EXEC '{USER}.KICKSSYS.V1R5M0.CLIST(KICKS)'")
        s.enter()
        scr = s.wait_for(r"KSGM|KICKS|\*\*\*|NOT ALLOWED", timeout=180)
        for _ in range(40):
            if "KICKS version" not in scr and re.search(r"KSGM|K I C K S|KICKS FOR TSO", scr, re.I):
                break
            lines = [l for l in scr.splitlines() if l.strip()]
            if lines and lines[-1].strip() == "***":
                s.enter()
            time.sleep(3)
            scr = s.ascii()
        save("kicks-signon", scr)
        print(scr)
        if a.tran:
            s.clear()
            s.type(a.tran)
            s.enter()
            time.sleep(2)
            save(f"kicks-{a.tran.lower()}", s.ascii())
        s.close()


if __name__ == "__main__":
    main()
