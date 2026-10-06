package com.carddemo.xferfee.live.engine;

import java.util.List;

/** Starting state: account master, card xref and fee rules. */
public record EngineSeed(List<Account> accounts, List<CardXref> xref, List<FeeRule> rules) {
}
