package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** {@link FeeSchedule} backed by the {@code fee_rule} table (migrated CTL_XFER_PARM). */
public class JdbcFeeSchedule implements FeeSchedule {

    private final FeeRuleRepository repository;

    public JdbcFeeSchedule(FeeRuleRepository repository) {
        this.repository = repository;
    }

    /** Replaces the table with legacy rows exactly as given (no stricter checks). */
    @Override
    @Transactional
    public void seed(List<FeeRule> rules) {
        repository.deleteAll();
        rules.forEach(repository::insert);
    }

    /**
     * BR-06: the rule for {@code bookId} whose half-open window contains the transaction
     * business date. More than one match is an error, as the legacy singleton
     * {@code SELECT ... INTO} would fail.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        String book = FeeRules.normaliseBook(bookId);
        return FeeRules.single(book, businessDate, repository.findEffective(book, businessDate));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeeRule> rules() {
        return repository.findAll();
    }

    /**
     * Adds a rule through the API. Stricter than the legacy table, which only enforces the
     * {@code (BOOK_ID, EFF_DT)} key: empty windows and windows that overlap an existing rule for
     * the same book are rejected.
     */
    @Transactional
    public FeeRule addRule(FeeRule rule) {
        FeeRules.validateNew(rule);
        FeeRule normalised = FeeRules.normalise(rule);
        repository.lockBook(normalised.bookId());
        List<FeeRule> overlapping = repository.findByBook(normalised.bookId()).stream()
                .filter(existing -> FeeRules.overlaps(existing, normalised))
                .toList();
        if (!overlapping.isEmpty()) {
            throw new OverlappingFeeRuleException(normalised, overlapping);
        }
        repository.insert(normalised);
        return normalised;
    }
}
