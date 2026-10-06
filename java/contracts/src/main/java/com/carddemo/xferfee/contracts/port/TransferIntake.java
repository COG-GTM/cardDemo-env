package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.Account;
import java.util.List;

/** STEP010 / CBXFR01C: select transfers from the daily transaction file. */
public interface TransferIntake {

    IntakeResult extract(List<DailyTransaction> transactions, List<CardXref> xrefs, List<Account> accounts);
}
