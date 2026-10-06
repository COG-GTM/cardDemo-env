package com.carddemo.feeschedule;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeeScheduleService {

    private final FeeRuleRepository repository;

    public FeeScheduleService(FeeRuleRepository repository) {
        this.repository = repository;
    }

    /**
     * BR-06: the single rule for {@code bookId} whose window contains the transaction business date.
     * Zero matches is legacy SQLCODE 100; more than one is the singleton-SELECT failure.
     */
    @Transactional(readOnly = true)
    public FeeRule effectiveRule(String bookId, LocalDate businessDate) {
        List<FeeRule> matches = repository.findEffective(bookId, businessDate);
        if (matches.isEmpty()) {
            throw new FeeRuleNotFoundException(bookId, businessDate);
        }
        if (matches.size() > 1) {
            throw new AmbiguousFeeRuleException(bookId, businessDate, matches);
        }
        return matches.getFirst();
    }

    @Transactional(readOnly = true)
    public List<FeeRule> allRules() {
        return repository.findAll();
    }

    /** Kept, stricter: rejects empty or overlapping windows that CTL_XFER_PARM's PK would accept. */
    @Transactional
    public FeeRule addRule(FeeRule rule) {
        if (!rule.effDt().isBefore(rule.expDt())) {
            throw new InvalidFeeRuleException(
                    "eff_dt " + rule.effDt() + " must be before exp_dt " + rule.expDt());
        }
        repository.lockBook(rule.bookId());
        List<FeeRule> overlapping = repository.findByBook(rule.bookId()).stream()
                .filter(rule::overlaps)
                .toList();
        if (!overlapping.isEmpty()) {
            throw new OverlappingFeeRuleException(rule, overlapping);
        }
        repository.insert(rule);
        return rule;
    }

    /** Replaces every rule with legacy rows verbatim (no stricter checks) so replays stay byte-equal. */
    @Transactional
    public void replaceAll(List<FeeRule> rules) {
        repository.deleteAll();
        rules.forEach(repository::insert);
    }
}
