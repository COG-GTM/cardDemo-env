package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.TransferIntake;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/** Replays one decoded fixture and writes {@code <work>/out}; exits 0 when the run itself completed. */
@Component
class ParityReplayRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ParityReplayRunner.class);

    private final TransferIntake intake;
    private final AccountPosting posting;
    private final Reconciliation reconciliation;
    private final FeeSchedule feeSchedule;
    private int exitCode;

    ParityReplayRunner(TransferIntake intake, AccountPosting posting, Reconciliation reconciliation,
            FeeSchedule feeSchedule) {
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
        this.feeSchedule = feeSchedule;
    }

    @Override
    public void run(String... args) throws Exception {
        ReplayOptions options = ReplayOptions.parse(args, System.getenv());
        CaseInput input = CaseInput.load(options.input());
        ChainOutput output = switch (options.mode()) {
            case INPROC -> new InProcessChain(intake, posting, reconciliation, feeSchedule)
                    .run(input, options.postingMode());
            case EVENTS -> new EventChain(options).run(input, options.postingMode());
        };
        clean(options.out());
        output.write(options.out());
        LOG.info("{} {} {}: wrote {}", options.caseName(), options.mode(), options.postingMode().wire(),
                options.out());
        exitCode = 0;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static void clean(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
