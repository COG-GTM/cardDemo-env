package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** BR-06 window semantics and the legacy column constraints of CTL_XFER_PARM. */
public final class FeeRules {

    /** Width of the legacy {@code BOOK_ID CHAR(10)} column. */
    public static final int BOOK_ID_LENGTH = 10;

    /** The recorder's dump order: {@code ORDER BY BOOK_ID, EFF_DT}. */
    public static final Comparator<FeeRule> TABLE_ORDER = Comparator
            .comparing((FeeRule rule) -> padBook(rule.bookId()))
            .thenComparing(FeeRule::effectiveDate);

    private static final BigDecimal PCT_LIMIT = new BigDecimal("10");
    private static final BigDecimal CAP_LIMIT = new BigDecimal("1000000000");

    private FeeRules() {
    }

    /** Same predicate as {@code XFERFEE}: {@code EFF_DT <= date AND EXP_DT > date}. */
    public static boolean isEffectiveOn(FeeRule rule, LocalDate date) {
        return !rule.effectiveDate().isAfter(date) && rule.expiryDate().isAfter(date);
    }

    /** Two rules for the same book whose half-open windows share at least one day. */
    public static boolean overlaps(FeeRule a, FeeRule b) {
        return normaliseBook(a.bookId()).equals(normaliseBook(b.bookId()))
                && a.effectiveDate().isBefore(b.expiryDate())
                && b.effectiveDate().isBefore(a.expiryDate());
    }

    /**
     * Book ids compare like {@code CHAR(10)}: trailing blanks are insignificant. Returns the
     * trimmed id used by the contracts.
     */
    public static String normaliseBook(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new InvalidFeeRuleException("book is required");
        }
        String book = bookId.stripTrailing();
        if (book.length() > BOOK_ID_LENGTH) {
            throw new InvalidFeeRuleException("book must be at most " + BOOK_ID_LENGTH + " characters");
        }
        return book;
    }

    public static String padBook(String bookId) {
        return String.format("%-" + BOOK_ID_LENGTH + "s", bookId);
    }

    /** A rule read from the legacy table, with the book id trimmed as the contracts require. */
    public static FeeRule normalise(FeeRule rule) {
        return new FeeRule(normaliseBook(rule.bookId()), rule.feePct(), rule.feeCap(),
                rule.effectiveDate(), rule.expiryDate());
    }

    /**
     * Legacy SELECT ... INTO semantics: zero rows is "no rule" (BR-07), more than one row is an
     * error rather than an arbitrary pick.
     */
    static java.util.Optional<FeeRule> single(String book, LocalDate date, List<FeeRule> matches) {
        if (matches.size() > 1) {
            throw new AmbiguousFeeRuleException(book, date, matches);
        }
        return matches.stream().findFirst();
    }

    /** Constraints for rules added through the API (stricter than the legacy table). */
    static void validateNew(FeeRule rule) {
        if (rule == null || rule.feePct() == null || rule.feeCap() == null
                || rule.effectiveDate() == null || rule.expiryDate() == null) {
            throw new InvalidFeeRuleException("bookId, feePct, feeCap, effectiveDate and expiryDate are required");
        }
        normaliseBook(rule.bookId());
        if (!rule.effectiveDate().isBefore(rule.expiryDate())) {
            throw new InvalidFeeRuleException("effectiveDate must be before expiryDate");
        }
        if (!fits(rule.feePct(), 6, PCT_LIMIT)) {
            throw new InvalidFeeRuleException("feePct must fit NUMERIC(7,6)");
        }
        if (!fits(rule.feeCap(), 2, CAP_LIMIT)) {
            throw new InvalidFeeRuleException("feeCap must fit NUMERIC(11,2)");
        }
    }

    private static boolean fits(BigDecimal value, int scale, BigDecimal limit) {
        return value.stripTrailingZeros().scale() <= scale && value.abs().compareTo(limit) < 0;
    }
}
