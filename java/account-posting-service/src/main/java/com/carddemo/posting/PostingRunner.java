package com.carddemo.posting;

import com.carddemo.contracts.TransferPosted;
import com.carddemo.contracts.TransferRejected;
import com.carddemo.contracts.TransferRequested;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Posts a run of transfers in the configured {@link PostingMode}. */
@Component
public class PostingRunner {

    private final PostingService service;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final PostingMode mode;

    public PostingRunner(PostingService service, OutboxRepository outbox, PlatformTransactionManager txManager,
            PostingProperties properties) {
        this.service = service;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.mode = properties.mode();
    }

    public PostingMode mode() {
        return mode;
    }

    public RunResult run(List<TransferRequested> transfers) {
        return mode == PostingMode.BATCH_ATOMIC ? runBatchAtomic(transfers) : runPerTransfer(transfers);
    }

    private RunResult runBatchAtomic(List<TransferRequested> transfers) {
        List<TransferPosted> posted = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> transfers.forEach(t -> posted.add(service.post(t))));
            return new RunResult(RunResult.RC_OK, true, posted, List.of(), List.of());
        } catch (PostingException e) {
            return new RunResult(RunResult.RC_ABEND, false, posted, List.of(rejection(e)),
                    List.of(e.legacyMessage()));
        } catch (RuntimeException e) {
            return new RunResult(RunResult.RC_ABEND, false, posted, List.of(), List.of(String.valueOf(e)));
        }
    }

    private RunResult runPerTransfer(List<TransferRequested> transfers) {
        List<TransferPosted> posted = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (TransferRequested transfer : transfers) {
            try {
                posted.add(tx.execute(status -> service.post(transfer)));
            } catch (PostingException e) {
                TransferRejected rejection = rejection(e);
                tx.executeWithoutResult(status -> outbox.append(rejection));
                rejected.add(rejection);
                diagnostics.add(e.legacyMessage());
            }
        }
        int rc = rejected.isEmpty() ? RunResult.RC_OK : RunResult.RC_REJECTS;
        return new RunResult(rc, true, posted, rejected, diagnostics);
    }

    private static TransferRejected rejection(PostingException e) {
        return new TransferRejected(e.tranId(), e.reason().name(), e.legacyMessage());
    }
}
