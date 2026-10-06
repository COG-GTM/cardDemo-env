package com.carddemo.xferfee.legacy.io;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A GDG base materialised as files {@code <dsn>.GnnnnV00} in one directory, the layout used by the
 * local JCL runner and the recorded fixtures.
 */
public final class GenerationDataGroup {

    private final Path directory;
    private final String baseDsn;
    private final Pattern generation;

    public GenerationDataGroup(Path directory, String baseDsn) {
        this.directory = directory;
        this.baseDsn = baseDsn;
        this.generation = Pattern.compile(Pattern.quote(baseDsn) + "\\.G(\\d{4})V00");
    }

    public String baseDsn() {
        return baseDsn;
    }

    /** Relative generation (0). */
    public Optional<Path> current() {
        int number = currentNumber();
        return number == 0 ? Optional.empty() : Optional.of(path(number));
    }

    /** Path the next (+1) generation would be cataloged under. */
    public Path next() {
        int number = currentNumber() + 1;
        if (number > 9999) {
            throw new IllegalStateException(baseDsn + ": generation number exhausted");
        }
        return path(number);
    }

    /**
     * Writes the (+1) generation atomically. Mirrors {@code DISP=(NEW,CATLG,DELETE)}: if the writer
     * fails, nothing is cataloged.
     */
    public Path writeNext(DatasetWriter writer) {
        Path target = next();
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "." + baseDsn, ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of an uncataloged generation
                }
            }
        }
    }

    private Path path(int number) {
        return directory.resolve(String.format("%s.G%04dV00", baseDsn, number));
    }

    private int currentNumber() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> generation.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    public interface DatasetWriter {
        void write(OutputStream out) throws IOException;
    }
}
