package com.carddemo.xferfee.legacy.codec;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field values of one record plus everything needed to re-encode it byte for byte: the sign style
 * of each numeric item and the raw bytes of each FILLER.
 */
public final class DecodedRecord {

    private final CopybookLayout layout;
    private final Map<String, Object> values;
    private final Map<String, SignStyle> signStyles;
    private final Map<String, byte[]> fillers;

    DecodedRecord(CopybookLayout layout, Map<String, Object> values, Map<String, SignStyle> signStyles,
            Map<String, byte[]> fillers) {
        this.layout = layout;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.signStyles = Collections.unmodifiableMap(new LinkedHashMap<>(signStyles));
        this.fillers = Collections.unmodifiableMap(new LinkedHashMap<>(fillers));
    }

    public CopybookLayout layout() {
        return layout;
    }

    /** Non-FILLER values: {@link String} for PIC X (untrimmed), {@link BigDecimal} for numerics. */
    public Map<String, Object> values() {
        return values;
    }

    public Map<String, SignStyle> signStyles() {
        return signStyles;
    }

    public Map<String, byte[]> fillers() {
        return fillers;
    }

    /** Raw PIC X content including trailing spaces. */
    public String text(String field) {
        return (String) require(field);
    }

    /** PIC X content with trailing spaces removed. */
    public String string(String field) {
        return text(field).replaceFirst(" +$", "");
    }

    public BigDecimal decimal(String field) {
        return (BigDecimal) require(field);
    }

    public long longValue(String field) {
        return decimal(field).longValueExact();
    }

    public int intValue(String field) {
        return decimal(field).intValueExact();
    }

    public SignStyle signStyle(String field) {
        SignStyle style = signStyles.get(field);
        if (style == null) {
            throw new IllegalArgumentException(layout.name() + "." + field + " is not numeric");
        }
        return style;
    }

    private Object require(String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalArgumentException(layout.name() + " has no field " + field);
        }
        return value;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(layout.name()).append(values);
        fillers.forEach((name, bytes) -> out.append(' ').append(name).append('=').append(Arrays.toString(bytes)));
        return out.toString();
    }
}
