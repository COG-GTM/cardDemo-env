package com.carddemo.xferfee.relay;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outbox schemas to drain (one per service) and the batch size per poll. */
@ConfigurationProperties("xferfee.relay")
public record RelayProperties(List<String> schemas, int batchSize) {

    public RelayProperties {
        schemas = schemas == null ? List.of("fee_schedule", "intake", "posting", "recon") : List.copyOf(schemas);
        batchSize = batchSize <= 0 ? 500 : batchSize;
    }
}
