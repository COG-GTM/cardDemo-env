package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** CTL_XFER_PARM held in memory; used by the in-process replay and as the lookup core of the service. */
public final class InMemoryFeeSchedule implements FeeSchedule {

    private final List<FeeRule> rules = new CopyOnWriteArrayList<>();

    @Override
    public void seed(List<FeeRule> newRules) {
        rules.clear();
        rules.addAll(FeeRules.ordered(newRules));
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        return FeeRules.effective(rules, bookId, businessDate);
    }

    @Override
    public List<FeeRule> rules() {
        return List.copyOf(rules);
    }
}
