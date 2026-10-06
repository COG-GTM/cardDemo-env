"""One parity run: reset both systems, stream every transaction to both, compare, summarize."""

from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from typing import Any, Callable

from candidate import judge, judge_naive, write_candidate
from cobol_driver import CobolChainDriver
from common import (
    TRANSFER_TYPE, TYPE_LABELS, WORK, decode_record, money, pct, psql_rows, raw_records, records,
)
from java_client import JavaEngineClient
from streams import Stream, prepare
from verdict import compare

Emit = Callable[[str, dict[str, Any]], None]
TRAN_LENGTH = 350


def decode_tran(record: bytes) -> dict[str, Any]:
    row = decode_record("CVTRA05Y", record)
    return {
        "tranId": row["TRAN-ID"].strip(),
        "typeCd": row["TRAN-TYPE-CD"],
        "cardNum": row["TRAN-CARD-NUM"],
        "desc": row["TRAN-DESC"].rstrip(),
        "amount": money(row["TRAN-AMT"]),
        "origTs": row["TRAN-ORIG-TS"].strip(),
    }


def build_seed(stream: Stream) -> dict[str, Any]:
    accounts = [
        {
            "acctId": int(row["ACCT-ID"]),
            "activeStatus": row["ACCT-ACTIVE-STATUS"],
            "currBal": money(row["ACCT-CURR-BAL"]),
            "creditLimit": money(row["ACCT-CREDIT-LIMIT"]),
            "cashCreditLimit": money(row["ACCT-CASH-CREDIT-LIMIT"]),
            "openDate": row["ACCT-OPEN-DATE"],
            "expirationDate": row["ACCT-EXPIRAION-DATE"],
            "reissueDate": row["ACCT-REISSUE-DATE"],
            "currCycCredit": money(row["ACCT-CURR-CYC-CREDIT"]),
            "currCycDebit": money(row["ACCT-CURR-CYC-DEBIT"]),
            "addrZip": row["ACCT-ADDR-ZIP"],
            "groupId": row["ACCT-GROUP-ID"].strip(),
        }
        for row in records(stream.inputs / "ACCTDATA.PS", "CVACT01Y")
    ]
    xref = [
        {"cardNum": row["XREF-CARD-NUM"], "custId": int(row["XREF-CUST-ID"]),
         "acctId": int(row["XREF-ACCT-ID"])}
        for row in records(stream.inputs / "CARDXREF.PS", "CVACT03Y")
    ]
    rules = [
        {"bookId": row["BOOK_ID"].strip(), "feePct": pct(row["FEE_PCT"]),
         "feeCap": money(row["FEE_CAP"]), "effDt": row["EFF_DT"], "expDt": row["EXP_DT"]}
        for row in psql_rows("SELECT BOOK_ID, FEE_PCT, FEE_CAP, EFF_DT, EXP_DT "
                             "FROM CTL_XFER_PARM ORDER BY BOOK_ID, EFF_DT")
    ]
    return {"accounts": accounts, "xref": xref, "rules": rules}


def normalize_java(result: dict[str, Any], elapsed_ms: int) -> dict[str, Any]:
    side: dict[str, Any] = {
        "engine": "Spring Boot java-live",
        "durationMs": elapsed_ms,
        "outcome": result["outcome"],
        "reason": f"{result['rule']}: {result['reason']}",
        "roundingMode": result["roundingMode"],
    }
    if result["outcome"] != "POSTED":
        return side
    rule, ledger = result["feeRule"], result["ledger"]
    src, tgt = result["sourceAfter"], result["targetAfter"]
    side.update(
        book=result["book"],
        businessDate=result["businessDate"],
        rule={"effDt": rule["effDt"], "pct": pct(rule["feePct"]), "cap": money(rule["feeCap"])},
        amount=money(result["amount"]),
        fee=money(result["fee"]),
        capApplied=result["capApplied"],
        source={"id": src["acctId"], "bal": money(src["currBal"]),
                "cycDebit": money(src["currCycDebit"])},
        target={"id": tgt["acctId"], "bal": money(tgt["currBal"]),
                "cycCredit": money(tgt["currCycCredit"])},
        ledger={
            "TRAN_ID": ledger["tranId"],
            "TRAN_DT": ledger["tranDt"],
            "SRC_ACCT_ID": str(ledger["srcAcctId"]),
            "TGT_ACCT_ID": str(ledger["tgtAcctId"]),
            "BOOK_ID": ledger["bookId"].strip(),
            "TRAN_AMT": money(ledger["tranAmt"]),
            "FEE_AMT": money(ledger["feeAmt"]),
            "CAP_APPLIED": ledger["capApplied"],
        },
    )
    return side


class Totals:
    def __init__(self, total: int):
        self.total = total
        self.transactions = 0
        self.transfers = 0
        self.ignored = 0
        self.matches = 0
        self.diffs = 0
        self.fee_diffs = 0
        self.cobol_fees = Decimal("0")
        self.java_fees = Decimal("0")

    def add(self, tran: dict[str, Any], cobol: dict[str, Any], java: dict[str, Any],
            verdict: dict[str, Any]) -> None:
        self.transactions += 1
        self.transfers += tran["typeCd"] == TRANSFER_TYPE
        self.ignored += verdict["ignored"]
        self.matches += verdict["match"]
        self.diffs += not verdict["match"]
        self.fee_diffs += "fee" in verdict["diffs"]
        self.cobol_fees += Decimal(cobol.get("fee") or "0")
        self.java_fees += Decimal(java.get("fee") or "0")

    def as_dict(self) -> dict[str, Any]:
        return {
            "total": self.total,
            "transactions": self.transactions,
            "transfers": self.transfers,
            "ignored": self.ignored,
            "matches": self.matches,
            "diffs": self.diffs,
            "feeDiffs": self.fee_diffs,
            "cobolFees": f"{self.cobol_fees:.2f}",
            "javaFees": f"{self.java_fees:.2f}",
        }


class ParitySession:
    def __init__(self, emit: Emit, cobol: CobolChainDriver | None = None,
                 java: JavaEngineClient | None = None):
        self.emit = emit
        self.cobol = cobol or CobolChainDriver()
        self.java = java or JavaEngineClient()
        self.stop_event = threading.Event()

    def stop(self) -> None:
        self.stop_event.set()

    def _java(self, tran: dict[str, Any]) -> dict[str, Any]:
        started = time.perf_counter()
        result = self.java.process(tran)
        return normalize_java(result, round((time.perf_counter() - started) * 1000))

    def run(self, stream_id: str, pace_ms: int = 600, count: int = 60, seed: int = 1250) -> dict[str, Any]:
        stream = prepare(stream_id, count, seed)
        tran_records = raw_records(stream.inputs / "DALYTRAN.PS", TRAN_LENGTH)
        self.emit("phase", {"text": "Resetting COBOL datasets, Db2 ledger and Java schema to the "
                                    "stream's starting state"})
        self.cobol.ensure_built()
        self.cobol.reset(stream.inputs)
        engine_seed = build_seed(stream)
        self.java.reset(engine_seed)
        self.emit("start", {
            "stream": stream.id,
            "description": stream.description,
            "recorded": stream.recorded,
            "total": len(tran_records),
            "paceMs": pace_ms,
            "accounts": engine_seed["accounts"],
            "rules": engine_seed["rules"],
            "rounding": self.java.rounding(),
        })
        totals = Totals(len(tran_records))
        rows: list[dict[str, Any]] = []
        roundings: set[str] = set()
        with ThreadPoolExecutor(max_workers=2) as pool:
            for seq, record in enumerate(tran_records, 1):
                if self.stop_event.is_set():
                    break
                tran = decode_tran(record)
                tran["typeLabel"] = TYPE_LABELS.get(tran["typeCd"], "Other")
                self.emit("pending", {"seq": seq, "tran": tran})
                cobol_future = pool.submit(self.cobol.process, record, tran)
                java_future = pool.submit(self._java, tran)
                cobol, java = cobol_future.result(), java_future.result()
                if "roundingMode" in java:
                    roundings.add(java["roundingMode"])
                verdict = compare(cobol, java)
                totals.add(tran, cobol, java, verdict)
                row = {"seq": seq, "tran": tran, "cobol": cobol, "java": java,
                       "verdict": verdict, "totals": totals.as_dict()}
                rows.append(row)
                self.emit("row", row)
                if seq < len(tran_records):
                    self.stop_event.wait(pace_ms / 1000)
        self.emit("phase", {"text": "Comparing end-of-stream state and running tools/parity/compare.py"})
        summary = self.finish(stream, len(tran_records), rows, totals, roundings, engine_seed)
        self.emit("done", summary)
        return summary

    def finish(self, stream: Stream, records_read: int, rows: list[dict[str, Any]], totals: Totals,
               roundings: set[str], engine_seed: dict[str, Any]) -> dict[str, Any]:
        stopped = len(rows) < records_read
        java_state = self.java.state()
        cobol_accounts = self.cobol.accounts()
        cobol_ledger = {row["TRAN_ID"].strip(): row for row in self.cobol.ledger()}
        state = end_state(cobol_accounts, cobol_ledger, java_state)

        baseline: dict[str, Any] = {}
        if stream.recorded and not stopped:
            steps_cobol = cobol_steps(rows)
            cobol_dir = write_candidate(
                WORK / "candidate" / stream.id / "cobol-live" / "candidate",
                records_read,
                sum(r["cobol"]["outcome"] == "SKIPPED" for r in rows),
                [extract_row(r["tran"], r["cobol"]) for r in rows if r["cobol"]["outcome"] == "POSTED"],
                [account_row(a) for a in cobol_accounts.values()],
                [fee_row(r["cobol"]) for r in rows if r["cobol"]["outcome"] == "POSTED"],
                engine_seed["rules"],
                steps_cobol,
            )
            java_dir = write_candidate(
                WORK / "candidate" / stream.id / "java" / "candidate",
                records_read,
                sum(r["java"]["outcome"] == "SKIPPED" for r in rows),
                [extract_row(r["tran"], r["java"]) for r in rows if r["java"]["outcome"] == "POSTED"],
                [java_account_row(a) for a in java_state["accounts"]],
                [fee_row(r["java"]) for r in rows if r["java"]["outcome"] == "POSTED"],
                [{key: str(value) for key, value in rule.items()} for rule in java_state["rules"]],
                java_steps(rows),
            )
            baseline["cobolLive"] = judge(stream.id, cobol_dir)
            baseline["java"] = judge(stream.id, java_dir)
            if roundings == {"HALF_EVEN"}:  # naive_ref is HALF_EVEN for every transaction
                naive = judge_naive(stream.id)
                baseline["naive"] = naive
                baseline["javaMatchesNaive"] = (
                    sorted(naive["diffRows"]) == sorted(baseline["java"]["diffRows"]))

        all_match = totals.diffs == 0 and state["equal"]
        if baseline:
            all_match = all_match and baseline["java"]["pass"]
        return {
            "stream": stream.id,
            "stopped": stopped,
            "totals": totals.as_dict(),
            "roundings": sorted(roundings),
            "state": state,
            "baseline": baseline,
            "verdict": "PARITY" if all_match and not stopped else ("STOPPED" if stopped else "DIFF"),
        }


def extract_row(tran: dict[str, Any], side: dict[str, Any]) -> dict[str, Any]:
    return {
        "XFR-TRAN-ID": tran["tranId"],
        "XFR-TRAN-DT": side["businessDate"],
        "XFR-SRC-ACCT-ID": int(side["source"]["id"]),
        "XFR-TGT-ACCT-ID": int(side["target"]["id"]),
        "XFR-BOOK-ID": side["book"],
        "XFR-TRAN-AMT": Decimal(side["amount"]),
        "XFR-CARD-NUM": tran["cardNum"],
    }


def fee_row(side: dict[str, Any]) -> dict[str, Any]:
    ledger = side["ledger"]
    return {
        "XFE-TRAN-ID": ledger["TRAN_ID"],
        "XFE-TRAN-DT": ledger["TRAN_DT"],
        "XFE-SRC-ACCT-ID": int(ledger["SRC_ACCT_ID"]),
        "XFE-TGT-ACCT-ID": int(ledger["TGT_ACCT_ID"]),
        "XFE-BOOK-ID": ledger["BOOK_ID"],
        "XFE-TRAN-AMT": Decimal(ledger["TRAN_AMT"]),
        "XFE-FEE-PCT": Decimal(side["rule"]["pct"]),
        "XFE-FEE-AMT": Decimal(ledger["FEE_AMT"]),
        "XFE-CAP-APPLIED": ledger["CAP_APPLIED"],
        "XFE-RULE-EFF-DT": side["rule"]["effDt"],
    }


def account_row(row: dict[str, Any]) -> dict[str, Any]:
    return dict(row)


def java_account_row(account: dict[str, Any]) -> dict[str, Any]:
    return {
        "ACCT-ID": int(account["acctId"]),
        "ACCT-ACTIVE-STATUS": account["activeStatus"],
        "ACCT-CURR-BAL": Decimal(str(account["currBal"])),
        "ACCT-CREDIT-LIMIT": Decimal(str(account["creditLimit"])),
        "ACCT-CASH-CREDIT-LIMIT": Decimal(str(account["cashCreditLimit"])),
        "ACCT-OPEN-DATE": account["openDate"],
        "ACCT-EXPIRAION-DATE": account["expirationDate"],
        "ACCT-REISSUE-DATE": account["reissueDate"],
        "ACCT-CURR-CYC-CREDIT": Decimal(str(account["currCycCredit"])),
        "ACCT-CURR-CYC-DEBIT": Decimal(str(account["currCycDebit"])),
        "ACCT-ADDR-ZIP": account["addrZip"],
        "ACCT-GROUP-ID": account["groupId"],
    }


def cobol_steps(rows: list[dict[str, Any]]) -> dict[str, int]:
    """Fold per-transaction return codes into the batch-equivalent job RC.

    A micro-batch holding only a non-transfer ends STEP030 with RC 4 (no fee records);
    for the whole stream that RC only applies if no transfer was posted at all.
    """
    steps: dict[str, int] = {}
    posted = [row for row in rows if row["cobol"]["outcome"] == "POSTED"]
    for row in rows:
        for step, code in row["cobol"].get("steps", {}).items():
            if step == "STEP030" and posted and row not in posted:
                continue
            steps[step] = max(steps.get(step, 0), code)
    return steps or {"STEP010": 0}


def java_steps(rows: list[dict[str, Any]]) -> dict[str, int]:
    outcomes = {row["java"]["outcome"] for row in rows}
    if "REJECTED" in outcomes:
        return {"STEP010": 0, "STEP020": 8}
    if "SKIPPED" in outcomes:
        return {"STEP010": 4}
    return {"STEP010": 0, "STEP020": 0, "STEP030": 0 if "POSTED" in outcomes else 4}


def end_state(cobol_accounts: dict[int, dict[str, Any]], cobol_ledger: dict[str, dict[str, str]],
              java_state: dict[str, Any]) -> dict[str, Any]:
    java_accounts = {int(a["acctId"]): a for a in java_state["accounts"]}
    account_diffs = []
    for acct_id in sorted(set(cobol_accounts) | set(java_accounts)):
        cobol, java = cobol_accounts.get(acct_id), java_accounts.get(acct_id)
        if cobol is None or java is None:
            account_diffs.append({"acct": acct_id, "field": "presence",
                                  "cobol": cobol is not None, "java": java is not None})
            continue
        for field, key in (("ACCT-CURR-BAL", "currBal"), ("ACCT-CURR-CYC-CREDIT", "currCycCredit"),
                           ("ACCT-CURR-CYC-DEBIT", "currCycDebit")):
            if money(cobol[field]) != money(java[key]):
                account_diffs.append({"acct": acct_id, "field": field,
                                      "cobol": money(cobol[field]), "java": money(java[key])})
    java_ledger = {row["tranId"]: row for row in java_state["ledger"]}
    ledger_diffs = []
    for tran_id in sorted(set(cobol_ledger) | set(java_ledger)):
        cobol, java = cobol_ledger.get(tran_id), java_ledger.get(tran_id)
        if cobol is None or java is None:
            ledger_diffs.append({"tranId": tran_id, "field": "presence",
                                 "cobol": cobol is not None, "java": java is not None})
            continue
        pairs = (("FEE_AMT", money(cobol["FEE_AMT"]), money(java["feeAmt"])),
                 ("TRAN_AMT", money(cobol["TRAN_AMT"]), money(java["tranAmt"])),
                 ("CAP_APPLIED", cobol["CAP_APPLIED"], java["capApplied"]),
                 ("SRC_ACCT_ID", int(cobol["SRC_ACCT_ID"]), int(java["srcAcctId"])),
                 ("TGT_ACCT_ID", int(cobol["TGT_ACCT_ID"]), int(java["tgtAcctId"])),
                 ("BOOK_ID", cobol["BOOK_ID"].strip(), java["bookId"].strip()),
                 ("TRAN_DT", cobol["TRAN_DT"], str(java["tranDt"])))
        for field, left, right in pairs:
            if left != right:
                ledger_diffs.append({"tranId": tran_id, "field": field, "cobol": left, "java": right})
    return {
        "accounts": len(cobol_accounts),
        "accountDiffs": account_diffs,
        "ledgerRows": len(cobol_ledger),
        "javaLedgerRows": len(java_ledger),
        "ledgerDiffs": ledger_diffs,
        "equal": not account_diffs and not ledger_diffs,
    }
