package com.carddemo.parity.console;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param repoRoot     cardDemo-env checkout (auto-detected when blank)
 * @param cobolCommand command that starts {@code parity-console/cobol/feed.py} next to GnuCOBOL + Postgres
 */
@ConfigurationProperties(prefix = "parity")
public record ConsoleProperties(String repoRoot, String cobolCommand) {

    public Path root() {
        if (repoRoot != null && !repoRoot.isBlank()) {
            return Path.of(repoRoot).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("fixtures")) ? cwd : cwd.getParent();
    }

    public Path fixtures() {
        return root().resolve("fixtures").resolve("xferfee");
    }
}
