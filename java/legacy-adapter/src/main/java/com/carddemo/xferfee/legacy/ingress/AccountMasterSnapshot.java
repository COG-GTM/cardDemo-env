package com.carddemo.xferfee.legacy.ingress;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.legacy.codec.DecodedRecord;
import com.carddemo.xferfee.legacy.record.LegacyRecords;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The account master (ACCTDATA.PS) in file order, keeping each record's original image so that
 * untouched fields can be written back byte for byte (BR-16: full new generation, never in place).
 */
public final class AccountMasterSnapshot {

    private final List<DecodedRecord> images;
    private final List<Account> accounts;

    public AccountMasterSnapshot(List<DecodedRecord> images) {
        this.images = List.copyOf(images);
        this.accounts = images.stream().map(LegacyRecords::account).toList();
    }

    public List<DecodedRecord> images() {
        return images;
    }

    public List<Account> accounts() {
        return accounts;
    }

    public int size() {
        return accounts.size();
    }

    /** First entry with this id: the lookup CBXFR01C uses to resolve the fee book (BR-03). */
    public Optional<Account> first(long accountId) {
        return accounts.stream().filter(a -> a.accountId() == accountId).findFirst();
    }

    /** Position of the last entry with this id: the entry XFERFEE posts to (2200-FIND-ACCOUNTS). */
    public int postingIndex(long accountId) {
        return postingIndex(accountId, accounts.size());
    }

    /** As {@link #postingIndex(long)}, within the first {@code limit} entries (XFERFEE's loaded table). */
    public int postingIndex(long accountId, int limit) {
        for (int i = Math.min(limit, accounts.size()) - 1; i >= 0; i--) {
            if (accounts.get(i).accountId() == accountId) {
                return i;
            }
        }
        return -1;
    }

    /** Positional account list with the posting entry of each updated account replaced. */
    public List<Account> withUpdates(Map<Long, Account> updated) {
        List<Account> result = new ArrayList<>(accounts);
        updated.forEach((id, account) -> {
            int index = postingIndex(id);
            if (index < 0) {
                throw new IllegalArgumentException("account " + id + " is not on the master");
            }
            result.set(index, account);
        });
        return result;
    }
}
