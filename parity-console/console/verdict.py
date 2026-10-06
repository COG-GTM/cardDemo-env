"""Field-by-field comparison of one transaction's COBOL and Java outcomes."""

from __future__ import annotations

from typing import Any

# (key, label) in display order; values are read with dotted paths.
FIELDS = [
    ("outcome", "Outcome"),
    ("book", "Book"),
    ("businessDate", "Business date"),
    ("rule.effDt", "Rule EFF_DT"),
    ("rule.pct", "Rule FEE_PCT"),
    ("rule.cap", "Rule FEE_CAP"),
    ("amount", "Amount"),
    ("fee", "Fee"),
    ("capApplied", "Cap applied"),
    ("source.id", "Source acct"),
    ("source.bal", "Source balance after"),
    ("source.cycDebit", "Source cycle debit"),
    ("target.id", "Target acct"),
    ("target.bal", "Target balance after"),
    ("target.cycCredit", "Target cycle credit"),
    ("ledger.TRAN_ID", "Ledger TRAN_ID"),
    ("ledger.TRAN_DT", "Ledger TRAN_DT"),
    ("ledger.SRC_ACCT_ID", "Ledger SRC_ACCT_ID"),
    ("ledger.TGT_ACCT_ID", "Ledger TGT_ACCT_ID"),
    ("ledger.BOOK_ID", "Ledger BOOK_ID"),
    ("ledger.TRAN_AMT", "Ledger TRAN_AMT"),
    ("ledger.FEE_AMT", "Ledger FEE_AMT"),
    ("ledger.CAP_APPLIED", "Ledger CAP_APPLIED"),
]


def lookup(side: dict[str, Any], key: str) -> Any:
    value: Any = side
    for part in key.split("."):
        if not isinstance(value, dict):
            return None
        value = value.get(part)
    return None if value is None else str(value)


def compare(cobol: dict[str, Any], java: dict[str, Any]) -> dict[str, Any]:
    fields = []
    diffs = []
    for key, label in FIELDS:
        left, right = lookup(cobol, key), lookup(java, key)
        same = left == right
        if left is None and right is None:
            continue
        fields.append({"key": key, "label": label, "cobol": left, "java": right, "match": same})
        if not same:
            diffs.append(key)
    ignored = cobol.get("outcome") == "IGNORED" and java.get("outcome") == "IGNORED"
    return {"match": not diffs, "ignored": ignored, "diffs": diffs, "fields": fields}
