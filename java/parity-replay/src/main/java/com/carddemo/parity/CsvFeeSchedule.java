package com.carddemo.parity;

import com.carddemo.contracts.FeeRule;
import com.carddemo.contracts.FeeSchedule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stand-in for fee-schedule-service (COG-1236): reads a fixture's CTL_XFER_PARM.csv and applies
 * the XFERFEE predicate {@code EFF_DT <= tranDate AND EXP_DT > tranDate}.
 */
@Component
public class CsvFeeSchedule implements FeeSchedule {

    private List<FeeRule> rules = List.of();

    public void load(Path csv) {
        try {
            rules = Files.readAllLines(csv).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .map(c -> new FeeRule(c[0], new BigDecimal(c[1].trim()), new BigDecimal(c[2].trim()),
                            LocalDate.parse(c[3].trim()), LocalDate.parse(c[4].trim())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<FeeRule> ruleFor(String bookId, LocalDate tranDate) {
        List<FeeRule> matches = rules.stream()
                .filter(r -> r.bookId().strip().equals(bookId.strip()))
                .filter(r -> !r.effectiveDate().isAfter(tranDate) && r.expiryDate().isAfter(tranDate))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("-811");
        }
        return matches.stream().findFirst();
    }
}
