package com.carddemo.xferfee.services.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.feeschedule.FeeRules;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** XFERFEE's {@code SELECT ... FROM CTL_XFER_PARM} with the same half-open window (BR-06/07). */
@Component
public class JdbcFeeSchedule implements FeeSchedule {

    private final JdbcTemplate jdbc;

    public JdbcFeeSchedule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void seed(List<FeeRule> rules) {
        jdbc.update("DELETE FROM fee_schedule.ctl_xfer_parm");
        for (FeeRule rule : FeeRules.ordered(rules)) {
            jdbc.update("INSERT INTO fee_schedule.ctl_xfer_parm (book_id, fee_pct, fee_cap, eff_dt, exp_dt) "
                    + "VALUES (?, ?, ?, ?, ?)", rule.bookId(), rule.feePct(), rule.feeCap(),
                    Date.valueOf(rule.effectiveDate()), Date.valueOf(rule.expiryDate()));
        }
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        return jdbc.query("SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt FROM fee_schedule.ctl_xfer_parm "
                + "WHERE book_id = ? AND eff_dt <= ? AND exp_dt > ? ORDER BY eff_dt LIMIT 1",
                JdbcFeeSchedule::row, bookId.stripTrailing(), Date.valueOf(businessDate), Date.valueOf(businessDate))
                .stream().findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return jdbc.query("SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt FROM fee_schedule.ctl_xfer_parm "
                + "ORDER BY book_id, eff_dt", JdbcFeeSchedule::row);
    }

    private static FeeRule row(ResultSet rs, int n) throws SQLException {
        return new FeeRule(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate());
    }
}
