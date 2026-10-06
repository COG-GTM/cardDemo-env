package com.carddemo.legacy.ingress;

import java.util.List;

/** Receives the account master and card cross-reference snapshots from the legacy extracts. */
public interface ReferenceDataLoader {
  void loadAccounts(List<Account> accounts);

  void loadCardXrefs(List<CardXref> xrefs);
}
