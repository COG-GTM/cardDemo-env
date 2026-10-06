package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.StepReport;
import java.util.List;

/** STEP020 SYSOUT exactly as XFERFEE DISPLAYs it. */
public final class PostingStepReport {

    public static final String STEP = "STEP020";

    private PostingStepReport() {
    }

    public static StepReport of(PostingRunResult result) {
        if (result.abended()) {
            return new StepReport(STEP, result.returnCode(),
                    List.of(result.abendMessage(), "XFERFEE: 9999-ABEND-PROGRAM"));
        }
        return new StepReport(STEP, result.returnCode(), List.of(
                "XFERFEE: TRANSFERS POSTED " + LegacyText.digits(result.posted().size(), 9),
                "XFERFEE: TOTAL FEES " + LegacyText.signed(result.totalFees(), 9, 2)));
    }
}
