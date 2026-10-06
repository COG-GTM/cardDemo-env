package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.observability.ChainRunReport;
import com.carddemo.xferfee.observability.DeadLetterQueue;
import com.carddemo.xferfee.observability.RecordingAlertPublisher;
import com.carddemo.xferfee.observability.RunArtifactsWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * {@code parity-replay --case <name> --out <dir>}: replays one fixture and writes
 * {@code sysout/STEP0x0.txt} (SYSOUT text), {@code sysout/STEP0x0.json} (counters, RC, rejects),
 * {@code rc.json} and {@code observability/} (alerts, DLQ, metrics snapshot) under {@code <dir>}.
 */
@Component
public class ReplayRunner implements ApplicationRunner {

    private final ChainReplay chain;
    private final RunArtifactsWriter writer;
    private final ObjectProvider<RecordingAlertPublisher> alerts;
    private final DeadLetterQueue deadLetters;
    private final MeterRegistry registry;

    public ReplayRunner(ChainReplay chain, RunArtifactsWriter writer, ObjectProvider<RecordingAlertPublisher> alerts,
            DeadLetterQueue deadLetters, MeterRegistry registry) {
        this.chain = chain;
        this.writer = writer;
        this.alerts = alerts;
        this.deadLetters = deadLetters;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.getSourceArgs().length == 0) {
            return;
        }
        ReplayOptions options = ReplayOptions.parse(args.getSourceArgs());
        Files.createDirectories(options.out());
        ChainRunReport report = chain.run(FixtureInputs.load(options));
        writer.writeSysout(report, options.out());
        RecordingAlertPublisher recorded = alerts.getIfAvailable();
        writer.writeOperational(recorded == null ? List.of() : recorded.alerts(), deadLetters, registry,
                options.out());
    }
}
