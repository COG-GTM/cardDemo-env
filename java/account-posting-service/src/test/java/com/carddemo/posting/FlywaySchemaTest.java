package com.carddemo.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FlywaySchemaTest {

    @Test
    void ledgerMigrationMatchesLegacyDdl() throws Exception {
        String legacy = Files.readString(Path.of("../../db2/ddl/XFER_FEE_LEDGER.sql"));
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V1__account_posting_schema.sql"));

        assertThat(normalize(migration)).contains(normalize(legacy));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }
}
