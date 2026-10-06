package com.carddemo.xfer.feeschedule;

import com.carddemo.xfer.contracts.FeeRule;
import com.carddemo.xfer.contracts.XferJson;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owns CTL_XFER_PARM. Mirrors the XFERFEE singleton SELECT: no row is SQLCODE +100 (404),
 * more than one row is SQLCODE -811 (409).
 */
@RestController
public class FeeRuleController {

    private final JdbcTemplate jdbc;

    public FeeRuleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/fee-rules/effective")
    public ResponseEntity<String> effective(@RequestParam String bookId, @RequestParam String date) {
        LocalDate tranDt;
        try {
            tranDt = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body("invalid date " + date);
        }
        List<FeeRule> rules = jdbc.query(
                "SELECT BOOK_ID, FEE_PCT, FEE_CAP, EFF_DT, EXP_DT FROM CTL_XFER_PARM "
                        + "WHERE BOOK_ID = ? AND EFF_DT <= ? AND EXP_DT > ?",
                (rs, i) -> new FeeRule(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate()),
                bookId, tranDt, tranDt);
        if (rules.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (rules.size() > 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("ambiguous rule for " + bookId);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(XferJson.write(rules.get(0)));
    }
}
