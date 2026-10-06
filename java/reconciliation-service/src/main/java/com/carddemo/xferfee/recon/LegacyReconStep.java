package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.util.List;
import java.util.Optional;

/** STEP030 with its JCL condition {@code COND=(4,LT,STEP020)} (BR-18). */
public final class LegacyReconStep {

    static final int COND_THRESHOLD = 4;

    private final LegacyReconReportRenderer renderer = new LegacyReconReportRenderer();

    /** Empty when the step is bypassed: posting ended with RC greater than 4. */
    public Optional<LegacyReconReport> run(List<TransferPosted> postedInFileOrder, int postingReturnCode) {
        if (isBypassed(postingReturnCode)) {
            return Optional.empty();
        }
        return Optional.of(renderer.render(postedInFileOrder));
    }

    public static boolean isBypassed(int postingReturnCode) {
        return COND_THRESHOLD < postingReturnCode;
    }
}
