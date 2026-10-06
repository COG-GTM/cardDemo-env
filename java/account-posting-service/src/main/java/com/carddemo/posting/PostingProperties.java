package com.carddemo.posting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code posting.mode}: per-transfer by default until the decision register settles it. */
@ConfigurationProperties(prefix = "posting")
public record PostingProperties(@DefaultValue("per-transfer") PostingMode mode) {
}
