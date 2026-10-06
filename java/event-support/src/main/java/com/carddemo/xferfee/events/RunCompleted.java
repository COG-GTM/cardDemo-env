package com.carddemo.xferfee.events;

import com.carddemo.xferfee.contracts.StepReport;
import java.util.List;

/** Emitted once the last step of a run has finished or was skipped by COND. */
public record RunCompleted(String runId, List<StepReport> steps) {
}
