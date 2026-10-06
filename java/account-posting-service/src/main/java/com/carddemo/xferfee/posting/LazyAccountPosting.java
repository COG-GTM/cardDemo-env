package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Resolves the upstream {@link FeeSchedule} (COG-1236) and {@link FeePolicy} (COG-1235) beans at
 * call time, so registration order between module auto-configurations does not matter.
 */
class LazyAccountPosting implements AccountPosting {

    private final ObjectProvider<FeeSchedule> feeSchedule;
    private final ObjectProvider<FeePolicy> feePolicy;
    private final PostingMode mode;

    LazyAccountPosting(ObjectProvider<FeeSchedule> feeSchedule, ObjectProvider<FeePolicy> feePolicy,
            PostingMode mode) {
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.mode = mode;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        return new InMemoryAccountPosting(feeSchedule.getObject(), feePolicy.getObject(), mode)
                .post(transfers, accountMaster, ledgerBefore);
    }
}
