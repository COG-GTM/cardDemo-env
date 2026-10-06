package com.carddemo.xferfee.legacy.ingress;

import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locations of the three input datasets. */
public record LegacyInputFiles(Path dailyTransactions, Path cardXref, Path accountMaster) {

    /**
     * Resolves the inputs in a directory holding either the short fixture names
     * ({@code DALYTRAN.PS}) or full DSNs ({@code AWS.M2.CARDDEMO.DALYTRAN.PS}).
     */
    public static LegacyInputFiles in(Path directory) {
        return new LegacyInputFiles(
                resolve(directory, "DALYTRAN.PS", LegacyCopybooks.DALYTRAN_DSN),
                resolve(directory, "CARDXREF.PS", LegacyCopybooks.CARDXREF_DSN),
                resolve(directory, "ACCTDATA.PS", LegacyCopybooks.ACCTDATA_DSN));
    }

    private static Path resolve(Path directory, String shortName, String dsn) {
        Path full = directory.resolve(dsn);
        return Files.exists(full) ? full : directory.resolve(shortName);
    }
}
