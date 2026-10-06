package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.feeschedule.AmbiguousFeeRuleException;
import com.carddemo.xferfee.feeschedule.FeeRuleSnapshots;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * {@code parity-replay --case <name> --out <dir> [--fixtures <dir>] [--lookups <csv>]}.
 *
 * <p>Replays a fixture through whichever contract beans are present and writes the raw Java
 * output under {@code --out}. Steps without a bean write nothing, so their outputs show up as
 * parity differences. Currently wired: {@link FeeSchedule} (seeded from {@code db2_before},
 * dumped to {@code db2_after/CTL_XFER_PARM.csv}, and queried for every {@code --lookups} row).
 */
@Component
class ParityReplay implements ApplicationRunner {

    static final String RESOLUTION_HEADER = "tran_id,book_id,tran_dt,status,fee_pct,fee_cap,eff_dt";

    private static final Logger LOG = LoggerFactory.getLogger(ParityReplay.class);

    private final ObjectProvider<FeeSchedule> feeSchedule;

    ParityReplay(ObjectProvider<FeeSchedule> feeSchedule) {
        this.feeSchedule = feeSchedule;
    }

    @Override
    public void run(ApplicationArguments arguments) throws IOException {
        ReplayArgs args = ReplayArgs.parse(arguments.getSourceArgs());
        Path caseDir = args.caseDir();
        if (!Files.isDirectory(caseDir)) {
            throw new IllegalArgumentException("unknown case: " + caseDir);
        }
        Path out = args.out();
        clean(out);
        Files.createDirectories(out.resolve("datasets"));
        Files.createDirectories(out.resolve("sysout"));

        FeeSchedule schedule = feeSchedule.getIfAvailable();
        if (schedule == null) {
            LOG.warn("no FeeSchedule bean: CTL_XFER_PARM and fee lookups not replayed");
        } else {
            FeeRuleSnapshots.seed(schedule, caseDir.resolve("db2_before"));
            if (args.lookups() != null) {
                resolveLookups(schedule, args.lookups(), out.resolve("fee_resolution.csv"));
            }
            FeeRuleSnapshots.dump(schedule, out.resolve("db2_after"));
        }
        // No step (STEP010-030) is wired yet, so no step return codes are reported.
        Files.writeString(out.resolve("rc.json"), "{\n  \"steps\": {},\n  \"maxcc\": 0\n}\n",
                StandardCharsets.UTF_8);
    }

    /** BR-06 lookups by book and transaction business date, as the posting step makes them. */
    private static void resolveLookups(FeeSchedule schedule, Path lookups, Path target) throws IOException {
        List<String> lines = Files.readAllLines(lookups, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(RESOLUTION_HEADER).append('\n');
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            result.append(cells[0]).append(',').append(cells[1]).append(',').append(cells[2]).append(',');
            try {
                Optional<FeeRule> rule = schedule.effectiveRule(cells[1], LocalDate.parse(cells[2]));
                if (rule.isPresent()) {
                    FeeRule found = rule.get();
                    result.append("FOUND,").append(found.feePct().toPlainString()).append(',')
                            .append(found.feeCap().toPlainString()).append(',').append(found.effectiveDate());
                } else {
                    result.append("NOT_FOUND,,,");
                }
            } catch (AmbiguousFeeRuleException e) {
                result.append("AMBIGUOUS,,,");
            }
            result.append('\n');
        }
        Files.writeString(target, result.toString(), StandardCharsets.UTF_8);
    }

    private static void clean(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(dir);
    }
}
