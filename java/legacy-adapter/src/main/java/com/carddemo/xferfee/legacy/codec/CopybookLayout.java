package com.carddemo.xferfee.legacy.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-length record layout parsed from a copybook. */
public final class CopybookLayout {

    private static final Map<String, CopybookLayout> CACHE = new ConcurrentHashMap<>();

    private final String name;
    private final List<FieldSpec> fields;
    private final Map<String, FieldSpec> byName;
    private final int recordLength;

    CopybookLayout(String name, List<FieldSpec> fields) {
        this.name = name;
        this.fields = List.copyOf(fields);
        this.byName = new LinkedHashMap<>();
        for (FieldSpec field : fields) {
            if (byName.put(field.name(), field) != null) {
                throw new IllegalArgumentException(name + ": duplicate field " + field.name());
            }
        }
        this.recordLength = fields.isEmpty() ? 0 : fields.get(fields.size() - 1).end();
    }

    /** Loads {@code copybook/<name>.cpy} from the classpath (copied from the repo's copybook dir). */
    public static CopybookLayout load(String copybook) {
        return CACHE.computeIfAbsent(copybook.toUpperCase(), CopybookLayout::loadResource);
    }

    private static CopybookLayout loadResource(String copybook) {
        String resource = "copybook/" + copybook + ".cpy";
        try (InputStream in = CopybookLayout.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("copybook not on classpath: " + resource);
            }
            return CopybookParser.parse(copybook, new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String name() {
        return name;
    }

    public List<FieldSpec> fields() {
        return fields;
    }

    public int recordLength() {
        return recordLength;
    }

    public FieldSpec field(String fieldName) {
        FieldSpec field = byName.get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(name + " has no field " + fieldName);
        }
        return field;
    }

    public boolean hasField(String fieldName) {
        return byName.containsKey(fieldName);
    }
}
