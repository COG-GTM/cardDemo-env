package com.carddemo.xferfee.contracts.replay;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Case state shared by {@link ReplayStage}s. Records are copybook field name to string value
 * (money as plain decimal strings); DB2 rows are column name (upper case) to string value.
 */
public interface ReplayContext {

    String caseName();

    LocalDate runDate();

    /** Decoded input dataset, e.g. {@code AWS.M2.CARDDEMO.DALYTRAN.PS}. Empty if absent. */
    List<Map<String, String>> input(String dsn);

    /** Output dataset written by an earlier stage. Empty if not written. */
    List<Map<String, String>> dataset(String dsn);

    /** Writes (replaces) an output dataset. Text datasets use a single {@code line} field. */
    void writeDataset(String dsn, List<Map<String, String>> records);

    /** Mutable rows of a DB2 table, seeded from the case's db2_before and dumped to db2_after. */
    List<Map<String, String>> table(String name);

    /** Appends a SYSOUT line for a step, written to sysout/{step}.txt. */
    void sysout(String step, String line);
}
