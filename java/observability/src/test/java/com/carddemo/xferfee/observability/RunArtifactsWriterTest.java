package com.carddemo.xferfee.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArtifactsWriterTest {

    @TempDir
    Path out;

    @Test
    void writesSysoutTextJsonRcAndOperationalFiles() throws Exception {
        CounterValue read = new CounterValue("RECORDS READ", XferMetrics.RECORDS_READ, "4", "000000004");
        ObservedStep step010 = new ObservedStep(ChainStep.STEP010, "CBXFR01C", true, 0, Severity.OK, List.of(read),
                List.of(), List.of("CBXFR01C: RECORDS READ 000000004"));
        ObservedStep step020 = new ObservedStep(ChainStep.STEP020, "XFERFEE", true, 8, Severity.ALERT, List.of(),
                List.of(), List.of("XFERFEE: 9999-ABEND-PROGRAM"));
        ObservedStep step030 = new ObservedStep(ChainStep.STEP030, "CBXFR03C", false, 0, Severity.OK, List.of(),
                List.of(), List.of());
        ChainRunReport report = new ChainRunReport("xferfee", "2024-06-30", List.of(step010, step020, step030), 8);
        RunArtifactsWriter writer = new RunArtifactsWriter();

        writer.writeSysout(report, out);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new XferMetrics(registry).recordsRead(4);
        InMemoryDeadLetterQueue dlq = new InMemoryDeadLetterQueue();
        dlq.put(new DeadLetterEntry("xferfee", "2024-06-30", ChainStep.STEP020, "XFERFEE", 8, "NO_FEE_RULE",
                List.of(), step020.sysout(), Instant.EPOCH));
        ChainAlert alert = new ChainAlert("xferfee", ChainStep.STEP020, "XFERFEE", 8, Severity.ALERT, "x",
                step020.sysout(), Instant.EPOCH);
        writer.writeOperational(List.of(alert), dlq, registry, out);

        assertThat(Files.readString(out.resolve("sysout/STEP010.txt"))).isEqualTo("CBXFR01C: RECORDS READ 000000004\n");
        assertThat(out.resolve("sysout/STEP030.txt")).doesNotExist();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(out.resolve("sysout/STEP010.json").toFile());
        assertThat(json.get("counters").get(0).get("label").asText()).isEqualTo("RECORDS READ");
        assertThat(json.get("counters").get(0).get("value").asText()).isEqualTo("4");
        JsonNode rc = mapper.readTree(out.resolve("rc.json").toFile());
        assertThat(rc.toString()).isEqualTo("{\"steps\":{\"STEP010\":0,\"STEP020\":8},\"maxcc\":8}");
        assertThat(Files.readAllLines(out.resolve("observability/alerts.jsonl"))).hasSize(1);
        assertThat(Files.readAllLines(out.resolve("observability/dlq.jsonl"))).singleElement()
                .satisfies(line -> assertThat(line).contains("\"reason\":\"NO_FEE_RULE\""));
        assertThat(Files.readString(out.resolve("observability/metrics.json")))
                .contains(XferMetrics.RECORDS_READ);
    }
}
