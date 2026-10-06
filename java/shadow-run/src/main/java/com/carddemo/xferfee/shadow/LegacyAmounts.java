package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The XFR-TRAN-AMT value XFERFEE computes with. CBXFR01C copies TRAN-AMT byte-for-byte into the extract, and
 * GnuCOBOL then reads those bytes with {@link Zoned#gnuDecode}; the contract amount alone cannot tell an
 * overpunched last digit from a plain ASCII one, so the reader is built from the raw DALYTRAN bytes.
 */
final class LegacyAmounts {

    private LegacyAmounts() {
    }

    /** Assumes every amount was written with standard overpunch (as {@code gen_fixtures.py} does). */
    static Function<TransferRequested, BigDecimal> standardOverpunch() {
        return xfer -> Zoned.gnuReadBack(xfer.amount(), 2);
    }

    /** Per-run reader over the raw TRAN-AMT bytes; transfers are consumed in input order. */
    static Function<TransferRequested, BigDecimal> fromRaw(List<DailyTransaction> transactions,
            List<String> rawAmounts) {
        Map<String, Deque<BigDecimal>> byKey = new HashMap<>();
        for (int i = 0; i < transactions.size(); i++) {
            DailyTransaction t = transactions.get(i);
            byKey.computeIfAbsent(key(t.tranId(), t.amount()), k -> new ArrayDeque<>())
                    .add(Zoned.gnuDecode(rawAmounts.get(i), 2));
        }
        return xfer -> {
            Deque<BigDecimal> values = byKey.get(key(xfer.tranId(), xfer.amount()));
            return values == null || values.isEmpty()
                    ? Zoned.gnuReadBack(xfer.amount(), 2)
                    : values.poll();
        };
    }

    private static String key(String tranId, BigDecimal amount) {
        return tranId.strip() + "|" + amount.stripTrailingZeros().toPlainString();
    }
}
