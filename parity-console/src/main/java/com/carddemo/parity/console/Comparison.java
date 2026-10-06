package com.carddemo.parity.console;

import com.carddemo.parity.engine.LedgerRow;
import com.carddemo.parity.engine.TransferOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Field-by-field MATCH / DIFF between a GnuCOBOL result and the Java outcome for one transaction. */
public final class Comparison {

    public record Field(String name, String cobol, String java, boolean match) {
    }

    private Comparison() {
    }

    public static List<Field> compare(JsonNode cobol, TransferOutcome java) {
        List<Field> fields = new ArrayList<>();
        boolean cobolSelected = cobol.path("selected").asBoolean(false);
        fields.add(text("selected", cobolSelected ? "posted" : "skipped", java.selected() ? "posted" : "skipped"));
        if (!cobolSelected && !java.selected()) {
            return fields;
        }
        fields.add(decimal("fee", cobol.path("fee"), java.fee()));
        fields.add(text("cap", cobol.path("capApplied").asText(null), java.capApplied()));
        fields.add(text("rule",
                rule(decimalOrNull(cobol.path("feePct")), cobol.path("ruleEffDate").asText(null)),
                rule(java.feePct(), java.ruleEffDate())));
        fields.add(decimal("srcBalanceAfter", cobol.path("srcBalanceAfter"), java.srcBalanceAfter()));
        fields.add(decimal("tgtBalanceAfter", cobol.path("tgtBalanceAfter"), java.tgtBalanceAfter()));
        fields.add(text("ledger", ledger(cobol.path("ledger")), ledger(java.ledger())));
        return fields;
    }

    public static boolean allMatch(List<Field> fields) {
        return fields.stream().allMatch(Field::match);
    }

    public static String rule(BigDecimal pct, String effDate) {
        if (pct == null) {
            return null;
        }
        return pct.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "% from " + effDate;
    }

    static String ledger(JsonNode row) {
        if (row == null || row.isMissingNode() || row.isNull()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        row.fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));
        return normalisedLedger(values.get("tranId"), values.get("tranDate"), values.get("srcAcctId"),
                values.get("tgtAcctId"), values.get("bookId"), values.get("tranAmt"), values.get("feeAmt"),
                values.get("capApplied"));
    }

    static String ledger(LedgerRow row) {
        if (row == null) {
            return null;
        }
        return normalisedLedger(row.tranId(), row.tranDate(), Long.toString(row.srcAcctId()),
                Long.toString(row.tgtAcctId()), row.bookId(), row.tranAmt().toPlainString(),
                row.feeAmt().toPlainString(), row.capApplied());
    }

    private static String normalisedLedger(String... cols) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            String value = cols[i] == null ? "" : cols[i].strip();
            if (i == 2 || i == 3) {
                value = Long.toString(Long.parseLong(value));
            } else if (i == 5 || i == 6) {
                value = new BigDecimal(value).setScale(2).toPlainString();
            }
            out.add(value);
        }
        return String.join(" | ", out);
    }

    private static Field text(String name, String cobol, String java) {
        return new Field(name, cobol, java, Objects.equals(cobol, java));
    }

    private static Field decimal(String name, JsonNode cobol, BigDecimal java) {
        BigDecimal left = decimalOrNull(cobol);
        boolean match = left == null ? java == null : java != null && left.compareTo(java) == 0;
        return new Field(name, left == null ? null : left.setScale(2).toPlainString(),
                java == null ? null : java.setScale(2).toPlainString(), match);
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return new BigDecimal(node.asText());
    }
}
