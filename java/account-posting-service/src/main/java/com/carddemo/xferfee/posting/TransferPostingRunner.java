package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Posts transfers in input order (BR-17) under the configured {@link PostingMode}. */
public class TransferPostingRunner {

    private final TransferPostingService service;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final PostingMode defaultMode;

    public TransferPostingRunner(TransferPostingService service, OutboxRepository outbox,
            PlatformTransactionManager transactionManager, PostingMode defaultMode) {
        this.service = service;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.defaultMode = defaultMode;
    }

    public PostingMode defaultMode() {
        return defaultMode;
    }

    public PostingRunResult run(List<TransferRequested> transfers) {
        return run(transfers, defaultMode);
    }

    public PostingRunResult run(List<TransferRequested> transfers, PostingMode mode) {
        return switch (mode) {
            case BATCH_ATOMIC -> runBatchAtomic(transfers);
            case PER_TRANSFER -> runPerTransfer(transfers);
        };
    }

    /** XFERFEE semantics: one commit at the end; the first failure rolls back the whole run (BR-15). */
    private PostingRunResult runBatchAtomic(List<TransferRequested> transfers) {
        try {
            List<TransferPosted> posted = tx.execute(status -> {
                List<TransferPosted> done = new ArrayList<>();
                for (TransferRequested transfer : transfers) {
                    done.add(service.post(transfer));
                }
                return done;
            });
            return new PostingRunResult(0, posted, List.of(), null);
        } catch (PostingException e) {
            return new PostingRunResult(8, List.of(), List.of(), e.getMessage());
        } catch (DataAccessException e) {
            return new PostingRunResult(8, List.of(), List.of(), "XFERFEE: SQL ERROR " + e.getMessage());
        }
    }

    /** Target default (pending D1/D2/D3): each transfer commits alone, failures are parked. */
    private PostingRunResult runPerTransfer(List<TransferRequested> transfers) {
        List<TransferPosted> posted = new ArrayList<>();
        List<TransferRejected> rejected = new ArrayList<>();
        for (TransferRequested transfer : transfers) {
            try {
                posted.add(tx.execute(status -> service.post(transfer)));
            } catch (PostingException e) {
                rejected.add(park(new TransferRejected(transfer.tranId(), transfer.cardNumber(), e.reason(), e.getMessage())));
            } catch (DataAccessException e) {
                rejected.add(park(new TransferRejected(transfer.tranId(), transfer.cardNumber(),
                        RejectReason.POSTING_ERROR, e.getMessage())));
            }
        }
        return new PostingRunResult(rejected.isEmpty() ? 0 : 4, posted, rejected, null);
    }

    private TransferRejected park(TransferRejected rejection) {
        tx.executeWithoutResult(status -> outbox.append(rejection));
        return rejection;
    }
}
