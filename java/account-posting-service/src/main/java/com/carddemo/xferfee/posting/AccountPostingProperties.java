package com.carddemo.xferfee.posting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mode}: commit unit for the service (per-transfer is the target default pending
 * COG-1249 / decision D1). {@code replayMode}: commit unit for the {@code AccountPosting} step
 * used by parity-replay, batch-atomic to reproduce XFERFEE's single commit (BR-15).
 */
@ConfigurationProperties("xferfee.posting")
public record AccountPostingProperties(
        @DefaultValue("per-transfer") PostingMode mode,
        @DefaultValue("batch-atomic") PostingMode replayMode) {
}
