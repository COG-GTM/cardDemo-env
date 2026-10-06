package com.carddemo.feeschedule;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeeRuleRepository {

    private static final RowMapper<FeeRule> ROW_MAPPER = (rs, rowNum) -> new FeeRule(
            rs.getString("book_id"),
            rs.getBigDecimal("fee_pct"),
            rs.getBigDecimal("fee_cap"),
            rs.getObject("eff_dt", LocalDate.class),
            rs.getObject("exp_dt", LocalDate.class));

    private final JdbcClient jdbc;

    public FeeRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Same predicate as XFERFEE.cbl 1200-GET-FEE-RULE: half-open {@code [eff_dt, exp_dt)}. */
    public List<FeeRule> findEffective(String bookId, LocalDate date) {
        return jdbc.sql("""
                SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt
                  FROM fee_rule
                 WHERE book_id = CAST(:book AS CHAR(10))
                   AND eff_dt <= :date
                   AND exp_dt > :date
                 ORDER BY eff_dt
                """)
                .param("book", bookId)
                .param("date", date)
                .query(ROW_MAPPER)
                .list();
    }

    public List<FeeRule> findByBook(String bookId) {
        return jdbc.sql("""
                SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt
                  FROM fee_rule
                 WHERE book_id = CAST(:book AS CHAR(10))
                 ORDER BY eff_dt
                """)
                .param("book", bookId)
                .query(ROW_MAPPER)
                .list();
    }

    /** Ordered like the recorder's CTL_XFER_PARM dump ({@code ORDER BY BOOK_ID, EFF_DT}). */
    public List<FeeRule> findAll() {
        return jdbc.sql("""
                SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt
                  FROM fee_rule
                 ORDER BY book_id, eff_dt
                """)
                .query(ROW_MAPPER)
                .list();
    }

    public void insert(FeeRule rule) {
        jdbc.sql("""
                INSERT INTO fee_rule (book_id, fee_pct, fee_cap, eff_dt, exp_dt)
                VALUES (CAST(:book AS CHAR(10)), :pct, :cap, :eff, :exp)
                """)
                .param("book", rule.bookId())
                .param("pct", rule.feePct())
                .param("cap", rule.feeCap())
                .param("eff", rule.effDt())
                .param("exp", rule.expDt())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM fee_rule").update();
    }

    /** Serialises concurrent inserts for one book so the overlap check cannot race. */
    public void lockBook(String bookId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:book))")
                .param("book", "fee_rule:" + bookId)
                .query()
                .singleRow();
    }
}
