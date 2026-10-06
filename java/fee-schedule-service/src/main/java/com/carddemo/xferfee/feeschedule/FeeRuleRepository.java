package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC access to {@code fee_rule}. Book ids are bound as {@code CHAR(10)} so
 * comparisons use the same blank-padded semantics as the legacy host variable
 * {@code WS-XFR-BOOK-ID PIC X(10)}.
 */
public class FeeRuleRepository {

    private static final String COLUMNS = "book_id, fee_pct, fee_cap, eff_dt, exp_dt";

    private final JdbcClient jdbc;

    public FeeRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Same predicate as {@code XFERFEE 2100-POST-ONE}: {@code EFF_DT <= date AND EXP_DT > date}. */
    public List<FeeRule> findEffective(String bookId, LocalDate date) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fee_rule"
                        + " WHERE book_id = CAST(:book AS CHAR(10))"
                        + " AND eff_dt <= :date AND exp_dt > :date"
                        + " ORDER BY eff_dt")
                .param("book", bookId)
                .param("date", date)
                .query(FeeRuleRepository::map)
                .list();
    }

    public List<FeeRule> findByBook(String bookId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fee_rule"
                        + " WHERE book_id = CAST(:book AS CHAR(10)) ORDER BY eff_dt")
                .param("book", bookId)
                .query(FeeRuleRepository::map)
                .list();
    }

    /** Ordered like the recorder's dump: {@code ORDER BY BOOK_ID, EFF_DT}. */
    public List<FeeRule> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fee_rule ORDER BY book_id, eff_dt")
                .query(FeeRuleRepository::map)
                .list();
    }

    public void insert(FeeRule rule) {
        jdbc.sql("INSERT INTO fee_rule (" + COLUMNS + ")"
                        + " VALUES (CAST(:book AS CHAR(10)), :pct, :cap, :eff, :exp)")
                .param("book", rule.bookId())
                .param("pct", rule.feePct())
                .param("cap", rule.feeCap())
                .param("eff", rule.effectiveDate())
                .param("exp", rule.expiryDate())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM fee_rule").update();
    }

    /** Serialises concurrent inserts for one book so the overlap check cannot race. */
    public void lockBook(String bookId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:book))")
                .param("book", "fee_rule:" + bookId)
                .query((rs, row) -> 0)
                .list();
    }

    private static FeeRule map(ResultSet rs, int row) throws SQLException {
        return new FeeRule(
                FeeRules.trimPadding(rs.getString("book_id")),
                rs.getBigDecimal("fee_pct"),
                rs.getBigDecimal("fee_cap"),
                rs.getObject("eff_dt", LocalDate.class),
                rs.getObject("exp_dt", LocalDate.class));
    }
}
