package com.carddemo.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CounterCatalogTest {

    @Test
    void enumMatchesSharedCatalog() throws Exception {
        JsonNode catalog = new ObjectMapper()
                .readTree(Path.of("../../ops/observability/counter-catalog.json").toFile());
        List<String> fromCatalog = new ArrayList<>();
        for (JsonNode c : catalog.get("counters")) {
            fromCatalog.add(String.join("|", c.get("id").asText(), c.get("step").asText(),
                    c.get("program").asText(), c.get("label").asText(), c.get("kind").asText(),
                    c.get("metric").asText()));
        }
        List<String> fromEnum = new ArrayList<>();
        for (LegacyCounter c : LegacyCounter.values()) {
            fromEnum.add(String.join("|", c.name(), c.step().name(), c.program(), c.label(),
                    c.kind().name().toLowerCase(), c.metric()));
        }
        assertEquals(fromCatalog, fromEnum);
    }
}
