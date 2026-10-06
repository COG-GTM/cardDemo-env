package com.carddemo.replay;

import com.carddemo.observability.StepOutcome;
import com.carddemo.observability.XferChainMetrics;
import java.util.ArrayList;
import java.util.List;

/** Runs STEP010-STEP030, stopping after the first non-zero RC like tools/runjcl. */
final class XferChainReplay {

    List<StepOutcome> run(FixtureCase fixture, XferChainMetrics metrics) {
        List<StepOutcome> outcomes = new ArrayList<>();
        ExtractStep.Result extract = new ExtractStep().run(fixture);
        if (!record(extract.outcome(), outcomes, metrics)) {
            return outcomes;
        }
        FeePostingStep.Result posting = new FeePostingStep().run(fixture, extract.transfers());
        if (!record(posting.outcome(), outcomes, metrics)) {
            return outcomes;
        }
        record(new ReconStep().run(posting.fees()), outcomes, metrics);
        return outcomes;
    }

    private static boolean record(StepOutcome outcome, List<StepOutcome> outcomes, XferChainMetrics metrics) {
        outcomes.add(outcome);
        metrics.record(outcome);
        return outcome.returnCode() == 0;
    }
}
