package com.carddemo.xfer.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies the shared xfer_java schema once per process, serialised across services. */
@Component
public class SchemaInitializer implements InitializingBean {

    private static final long LOCK_ID = 1_251_001L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SchemaInitializer(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void afterPropertiesSet() {
        String script = load();
        tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", LOCK_ID);
            for (String statement : script.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement);
                }
            }
        });
    }

    public static String load() {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/xfer-java-schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
