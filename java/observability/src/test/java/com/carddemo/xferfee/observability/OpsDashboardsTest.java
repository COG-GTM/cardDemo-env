package com.carddemo.xferfee.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.RejectReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Every metric the committed dashboards and alerts query must be one {@link XferMetrics} emits. */
class OpsDashboardsTest {

    private static final Path OPS = Path.of("..", "..", "ops");

    @Test
    void grafanaQueriesUsePrometheusNamesXferMetricsExposes() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        exercise(new XferMetrics(registry));
        Set<String> exposed = new TreeSet<>();
        Matcher sample = Pattern.compile("(?m)^(carddemo_xferfee_[a-z_]+)").matcher(registry.scrape());
        while (sample.find()) {
            exposed.add(sample.group(1));
        }
        for (String file : new String[] {"grafana/xferfee-chain-dashboard.json", "grafana/xferfee-alert-rules.json"}) {
            Set<String> queried = names(OPS.resolve(file), "carddemo_xferfee_[a-z_]+");
            assertThat(queried).as(file).isNotEmpty();
            assertThat(exposed).as(file).containsAll(queried);
        }
    }

    @Test
    void datadogQueriesUseMeterNamesXferMetricsRegisters() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        exercise(new XferMetrics(registry));
        Set<String> registered = registry.getMeters().stream().map(Meter::getId).map(Meter.Id::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        for (String file : new String[] {"datadog/xferfee-chain-dashboard.json", "datadog/xferfee-monitors.json"}) {
            new ObjectMapper().readTree(OPS.resolve(file).toFile());
            Set<String> queried = names(OPS.resolve(file), "carddemo\\.xferfee(?:\\.[a-z]+)+");
            assertThat(queried).as(file).isNotEmpty();
            assertThat(registered).as(file).containsAll(queried);
        }
        assertThat(registered).contains(XferMetrics.RECORDS_READ, XferMetrics.TRANSFERS_SELECTED,
                XferMetrics.UNMATCHED_CARDS, XferMetrics.TRANSFERS_POSTED, XferMetrics.TOTAL_FEES,
                XferMetrics.RECON_GRAND_TOTAL_FEE);
    }

    private static void exercise(XferMetrics metrics) {
        metrics.recordsRead(1);
        metrics.transfersSelected(1);
        metrics.transferPosted(BigDecimal.ONE);
        metrics.reconTotals(1, BigDecimal.ONE);
        metrics.transferRejected(ChainStep.STEP010, RejectReason.UNMATCHED_CARD);
        metrics.stepCompleted(ChainStep.STEP010, 4);
        metrics.stepCompleted(ChainStep.STEP020, 8);
        metrics.deadLettered(ChainStep.STEP020, 8);
        metrics.runMaxcc(8);
    }

    private static Set<String> names(Path file, String regex) throws Exception {
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile(regex).matcher(Files.readString(file));
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }
}
