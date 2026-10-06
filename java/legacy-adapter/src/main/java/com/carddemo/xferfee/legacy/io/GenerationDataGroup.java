package com.carddemo.xferfee.legacy.io;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A GDG base materialised as files {@code <dsn>.GnnnnV00} in one directory, cataloged by
 * {@code <dsn>.gdg} ({@code {"current": n}}) — the same layout and catalog the local JCL runner
 * ({@code tools/runjcl/runjcl.py}) resolves relative generations from.
 */
public final class GenerationDataGroup {

    private static final Pattern CURRENT = Pattern.compile("\"current\"\\s*:\\s*(\\d+)");

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

    /** The catalog file, {@code <dsn>.gdg}. */
    public Path catalog() {
        return directory.resolve(baseDsn + ".gdg");
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
     * Writes and catalogs the (+1) generation. Mirrors {@code DISP=(NEW,CATLG,DELETE)}: the data is
     * written to a temp file, linked into place only if that generation does not exist yet (so a
     * competing writer fails instead of overwriting), and the catalog is advanced last. Any failure
     * leaves neither a new generation nor a moved catalog.
     */
    public synchronized Path writeNext(DatasetWriter writer) {
        int number = currentNumber() + 1;
        if (number > 9999) {
            throw new IllegalStateException(baseDsn + ": generation number exhausted");
        }
        Path target = path(number);
        Path temp = null;
        boolean linked = false;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "." + baseDsn, ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            Files.createLink(target, temp);
            linked = true;
            writeCatalog(number);
            return target;
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException(target.getFileName() + " already exists but is not cataloged", e);
        } catch (IOException | RuntimeException e) {
            if (linked) {
                deleteQuietly(target);
            }
            throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    private void writeCatalog(int number) throws IOException {
        Path temp = Files.createTempFile(directory, "." + baseDsn, ".gdg.tmp");
        try {
            Files.writeString(temp, "{\"current\": " + number + "}\n", StandardCharsets.UTF_8);
            Files.move(temp, catalog(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(temp);
        }
    }

    private Path path(int number) {
        return directory.resolve(String.format("%s.G%04dV00", baseDsn, number));
    }

    /** The catalog when present (authoritative, as for runjcl), else the highest generation on disk. */
    private int currentNumber() {
        try {
            if (Files.exists(catalog())) {
                Matcher matcher = CURRENT.matcher(Files.readString(catalog(), StandardCharsets.UTF_8));
                if (!matcher.find()) {
                    throw new IllegalStateException(catalog() + ": no \"current\" entry");
                }
                return Integer.parseInt(matcher.group(1));
            }
            if (!Files.isDirectory(directory)) {
                return 0;
            }
            try (Stream<Path> files = Files.list(directory)) {
                return files.map(file -> generation.matcher(file.getFileName().toString()))
                        .filter(Matcher::matches)
                        .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                        .max()
                        .orElse(0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup of an uncataloged file
        }
    }

    @FunctionalInterface
    public interface DatasetWriter {
        void write(OutputStream out) throws IOException;
    }
}
