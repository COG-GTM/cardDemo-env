package com.carddemo.xferfee.observability;

import java.util.List;
import java.util.Optional;

public record ChainRunReport(String chain, String runDate, List<ObservedStep> steps, int maxcc) {

    public Optional<ObservedStep> step(ChainStep step) {
        return steps.stream().filter(report -> report.step() == step).findFirst();
    }
}
