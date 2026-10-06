package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fixed-length decoders for the three daily input members: CVTRA05Y, CVACT03Y and CVACT01Y. */
public final class LegacyInputs {

    static final int TRAN_LRECL = 350;
    static final int XREF_LRECL = 50;
    static final int ACCT_LRECL = 300;

    private LegacyInputs() {
    }

    /**
     * Daily transactions with TRAN-AMT as written (standard overpunch, like {@code copybook.py}). CBXFR01C moves it
     * byte-for-byte into the extract; the GnuCOBOL reading is applied where XFERFEE consumes it.
     */
    public static List<DailyTransaction> transactions(Path path) throws IOException {
        List<DailyTransaction> rows = new ArrayList<>();
        for (String r : records(path, TRAN_LRECL)) {
            rows.add(new DailyTransaction(r.substring(0, 16), r.substring(16, 18), (int) number(r.substring(18, 22)),
                    r.substring(22, 32), r.substring(32, 132), Zoned.decode(r.substring(132, 143), 2),
                    number(r.substring(143, 152)), r.substring(152, 202), r.substring(202, 252),
                    r.substring(252, 262), r.substring(262, 278), r.substring(278, 304), r.substring(304, 330)));
        }
        return rows;
    }

    public static List<CardXref> xrefs(Path path) throws IOException {
        List<CardXref> rows = new ArrayList<>();
        for (String r : records(path, XREF_LRECL)) {
            rows.add(new CardXref(r.substring(0, 16), number(r.substring(16, 25)), number(r.substring(25, 36))));
        }
        return rows;
    }

    /**
     * Account master rows with the balances XFERFEE actually loads into WS-ACCOUNT-TABLE (GnuCOBOL sign reading,
     * see {@link Zoned#gnuDecode}); {@link #rawAccounts} keeps the record bytes for fields it never recomputes.
     */
    public static List<Account> accounts(Path path) throws IOException {
        List<Account> rows = new ArrayList<>();
        for (String r : records(path, ACCT_LRECL)) {
            rows.add(new Account(number(r.substring(0, 11)), r.substring(11, 12),
                    Zoned.gnuDecode(r.substring(12, 24), 2), Zoned.gnuDecode(r.substring(24, 36), 2),
                    Zoned.gnuDecode(r.substring(36, 48), 2), r.substring(48, 58), r.substring(58, 68),
                    r.substring(68, 78), Zoned.gnuDecode(r.substring(78, 90), 2),
                    Zoned.gnuDecode(r.substring(90, 102), 2), r.substring(102, 112), r.substring(112, 122)));
        }
        return rows;
    }

    public static List<String> rawAccounts(Path path) throws IOException {
        return records(path, ACCT_LRECL);
    }

    private static long number(String digits) {
        String trimmed = digits.trim();
        return trimmed.isEmpty() ? 0 : Long.parseLong(trimmed);
    }

    private static List<String> records(Path path, int lrecl) throws IOException {
        String data = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        List<String> rows = new ArrayList<>();
        for (int offset = 0; offset + lrecl <= data.length(); offset += lrecl) {
            rows.add(data.substring(offset, offset + lrecl));
        }
        return rows;
    }
}
