package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * STEP020 fee pricing for replay: every XFER.FEES {@code XFE-FEE-AMT} / {@code XFE-CAP-APPLIED}
 * comes from the {@link FeePolicy} bean. Runs only while no {@link AccountPosting} bean (COG-1238)
 * is present; once posting lands it owns STEP020 and calls the same {@code FeePolicy}.
 *
 * <p>Usage: {@code --case=<name> --in=<dir> --out=<dir>}. {@code <in>} holds JSON lines prepared by
 * {@code tools/parity/java_candidate.py}: {@code recorded/XFER.EXTRACT.jsonl} (upstream stub for
 * STEP010) and {@code db2_before/{CTL_XFER_PARM,XFER_FEE_LEDGER}.jsonl}.
 */
@Component
class FeePricingReplay implements ApplicationRunner {

    static final String FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES";
    static final int RC_OK = 0;
    static final int RC_RULE_MISSING = 8;

    private static final Logger log = LoggerFactory.getLogger(FeePricingReplay.class);

    private final ObjectProvider<FeePolicy> feePolicy;
    private final ObjectProvider<FeeSchedule> feeSchedule;
    private final ObjectProvider<AccountPosting> accountPosting;
    private final ObjectMapper mapper;

    FeePricingReplay(ObjectProvider<FeePolicy> feePolicy, ObjectProvider<FeeSchedule> feeSchedule,
            ObjectProvider<AccountPosting> accountPosting, ObjectMapper mapper) {
        this.feePolicy = feePolicy;
        this.feeSchedule = feeSchedule;
        this.accountPosting = accountPosting;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption("in") || !args.containsOption("out")) {
            return;
        }
        Path in = Path.of(args.getOptionValues("in").get(0));
        Path out = Path.of(args.getOptionValues("out").get(0));
        FeePolicy policy = feePolicy.getIfAvailable();
        if (policy == null || accountPosting.getIfAvailable() != null) {
            log.info("fee pricing replay skipped (FeePolicy present={}, AccountPosting present={})",
                    policy != null, accountPosting.getIfAvailable() != null);
            return;
        }
        FeeSchedule schedule = feeSchedule.getIfAvailable(FixtureFeeSchedule::new);
        schedule.seed(read(in.resolve("db2_before/CTL_XFER_PARM.jsonl"), FeeRule.class));
        List<LedgerEntry> ledgerBefore = read(in.resolve("db2_before/XFER_FEE_LEDGER.jsonl"), LedgerEntry.class);
        List<TransferRequested> transfers = read(in.resolve("recorded/XFER.EXTRACT.jsonl"), TransferRequested.class);

        Optional<List<TransferPosted>> posted = price(transfers, schedule, policy);
        List<LedgerEntry> ledgerAfter = new ArrayList<>(ledgerBefore);
        posted.ifPresent(rows -> rows.stream().map(FeePricingReplay::ledgerEntry).forEach(ledgerAfter::add));

        posted.ifPresent(rows -> write(out.resolve("datasets/" + FEES_DSN + ".jsonl"), rows));
        write(out.resolve("db2_after/XFER_FEE_LEDGER.jsonl"), ledgerAfter);
        write(out.resolve("db2_after/CTL_XFER_PARM.jsonl"), schedule.rules());
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", Map.of("STEP020", posted.isPresent() ? RC_OK : RC_RULE_MISSING));
        Files.writeString(out.resolve("rc.json"), mapper.writeValueAsString(rc) + "\n");
        log.info("priced {} transfers with {} via {}", posted.map(List::size).orElse(0),
                policy.getClass().getSimpleName(), schedule.getClass().getSimpleName());
    }

    /** Empty when a transfer has no effective rule: BR-07 stops the run and BR-15 commits nothing. */
    static Optional<List<TransferPosted>> price(List<TransferRequested> transfers, FeeSchedule schedule,
            FeePolicy policy) {
        List<TransferPosted> posted = new ArrayList<>();
        for (TransferRequested transfer : transfers) {
            Optional<FeeRule> rule = schedule.effectiveRule(transfer.bookId(), transfer.tranDate());
            if (rule.isEmpty()) {
                return Optional.empty();
            }
            FeeResult fee = policy.apply(transfer.amount(), rule.get());
            posted.add(new TransferPosted(transfer.tranId(), transfer.tranDate(), transfer.sourceAccountId(),
                    transfer.targetAccountId(), transfer.bookId(), transfer.amount(), rule.get().feePct(),
                    fee.feeAmount(), fee.capApplied(), rule.get().effectiveDate()));
        }
        return Optional.of(posted);
    }

    private static LedgerEntry ledgerEntry(TransferPosted posted) {
        return new LedgerEntry(posted.tranId(), posted.tranDate(), posted.sourceAccountId(),
                posted.targetAccountId(), posted.bookId(), posted.amount(), posted.feeAmount(), posted.capApplied());
    }

    private <T> List<T> read(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                rows.add(mapper.readValue(line, type));
            }
        }
        return rows;
    }

    private void write(Path path, List<?> rows) {
        try {
            Files.createDirectories(path.getParent());
            StringBuilder text = new StringBuilder();
            for (Object row : rows) {
                text.append(mapper.writeValueAsString(row)).append('\n');
            }
            Files.writeString(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
