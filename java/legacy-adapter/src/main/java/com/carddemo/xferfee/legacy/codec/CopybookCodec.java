package com.carddemo.xferfee.legacy.codec;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Encodes and decodes fixed-length records described by one copybook. Thread-safe. */
public final class CopybookCodec {

    private final CopybookLayout layout;
    private final CodecOptions options;

    public CopybookCodec(CopybookLayout layout, CodecOptions options) {
        this.layout = layout;
        this.options = options;
    }

    public static CopybookCodec of(String copybook, CodecOptions options) {
        return new CopybookCodec(CopybookLayout.load(copybook), options);
    }

    public CopybookLayout layout() {
        return layout;
    }

    public CodecOptions options() {
        return options;
    }

    public DecodedRecord decode(byte[] record) {
        if (record.length != layout.recordLength()) {
            throw new CopybookDataException(layout.name() + ": record is " + record.length
                    + " bytes, layout is " + layout.recordLength());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, SignStyle> styles = new LinkedHashMap<>();
        Map<String, byte[]> fillers = new LinkedHashMap<>();
        Set<String> negativeZeros = new HashSet<>();
        for (FieldSpec field : layout.fields()) {
            if (field.isFiller()) {
                fillers.put(field.name(), Arrays.copyOfRange(record, field.offset(), field.end()));
                continue;
            }
            switch (field.kind()) {
                case ALPHANUMERIC -> values.put(field.name(), text(record, field));
                case ZONED -> {
                    ZonedDecimal.Decoded decoded = ZonedDecimal.decode(text(record, field), field);
                    values.put(field.name(), decoded.value());
                    styles.put(field.name(), decoded.style());
                    if (decoded.negativeZero()) {
                        negativeZeros.add(field.name());
                    }
                }
                case PACKED -> {
                    PackedDecimal.Decoded decoded = PackedDecimal.decode(record, field.offset(), field);
                    values.put(field.name(), decoded.value());
                    styles.put(field.name(), decoded.style());
                    if (decoded.negativeZero()) {
                        negativeZeros.add(field.name());
                    }
                }
            }
        }
        return new DecodedRecord(layout, values, styles, fillers, negativeZeros);
    }

    /** Splits a dataset image into records and decodes each one. */
    public List<DecodedRecord> decodeAll(byte[] dataset) {
        int lrecl = layout.recordLength();
        if (dataset.length % lrecl != 0) {
            throw new CopybookDataException(layout.name() + ": dataset size " + dataset.length
                    + " is not a multiple of " + lrecl);
        }
        List<DecodedRecord> records = new ArrayList<>(dataset.length / lrecl);
        for (int offset = 0; offset < dataset.length; offset += lrecl) {
            records.add(decode(Arrays.copyOfRange(dataset, offset, offset + lrecl)));
        }
        return records;
    }

    /** Re-encodes a decoded record exactly as it was read. */
    public byte[] encode(DecodedRecord record) {
        return encode(record.values(), record.signStyles(), record.fillers(), record.negativeZeros());
    }

    /** Encodes a fresh record using this codec's defaults for signs and FILLER. */
    public byte[] encode(Map<String, ?> values) {
        return encode(values, Map.of(), Map.of());
    }

    /**
     * Encodes a record. Every non-FILLER field must be present. Numeric items without an entry in
     * {@code signStyles} use the codec default; FILLERs without an entry in {@code fillers} are
     * filled with the codec's filler byte.
     */
    public byte[] encode(Map<String, ?> values, Map<String, SignStyle> signStyles, Map<String, byte[]> fillers) {
        return encode(values, signStyles, fillers, Set.of());
    }

    /**
     * As {@link #encode(Map, Map, Map)}; fields named in {@code negativeZeros} keep a negative sign
     * while their value is zero (see {@link DecodedRecord#negativeZeros()}).
     */
    public byte[] encode(Map<String, ?> values, Map<String, SignStyle> signStyles, Map<String, byte[]> fillers,
            Set<String> negativeZeros) {
        Set<String> unknown = new HashSet<>(values.keySet());
        byte[] record = new byte[layout.recordLength()];
        for (FieldSpec field : layout.fields()) {
            if (field.isFiller()) {
                byte[] filler = fillers.get(field.name());
                if (filler == null) {
                    Arrays.fill(record, field.offset(), field.end(), options.fillerByte());
                } else if (filler.length != field.length()) {
                    throw new CopybookDataException(field.name() + ": filler is " + filler.length + " bytes, expected " + field.length());
                } else {
                    System.arraycopy(filler, 0, record, field.offset(), field.length());
                }
                continue;
            }
            unknown.remove(field.name());
            Object value = values.get(field.name());
            if (value == null) {
                throw new CopybookDataException(layout.name() + ": missing value for " + field.name());
            }
            switch (field.kind()) {
                case ALPHANUMERIC -> putText(record, field, value.toString());
                case ZONED -> {
                    BigDecimal decimal = Numbers.toDecimal(value, field);
                    SignStyle style = signStyles.getOrDefault(field.name(),
                            field.signed() ? options.zonedSignStyle() : SignStyle.UNSIGNED);
                    putText(record, field, ZonedDecimal.encode(decimal, field, style,
                            negativeZeros.contains(field.name())));
                }
                case PACKED -> PackedDecimal.encode(Numbers.toDecimal(value, field), field,
                        signStyles.get(field.name()), negativeZeros.contains(field.name()), record, field.offset());
            }
        }
        if (!unknown.isEmpty()) {
            throw new CopybookDataException(layout.name() + ": unknown fields " + unknown);
        }
        return record;
    }

    private String text(byte[] record, FieldSpec field) {
        return new String(record, field.offset(), field.length(), options.charset());
    }

    private void putText(byte[] record, FieldSpec field, String text) {
        CharsetEncoder encoder = options.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes;
        try {
            bytes = encoder.encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new CopybookDataException(field.name() + ": cannot encode '" + text + "' in " + options.charset(), e);
        }
        if (bytes.remaining() > field.length()) {
            throw new CopybookDataException(field.name() + ": '" + text + "' exceeds " + field.length() + " bytes");
        }
        int length = bytes.remaining();
        bytes.get(record, field.offset(), length);
        if (length < field.length()) {
            byte space = options.charset().encode(" ").get();
            Arrays.fill(record, field.offset() + length, field.end(), space);
        }
    }
}
