package com.carddemo.xferfee.legacy.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the fixed-format copybook subset used by the transfer-fee chain: elementary items with
 * {@code PIC X}, {@code PIC [S]9[V9]} (DISPLAY or COMP-3) and {@code OCCURS n} on elementary items.
 * Anything else fails loudly rather than producing a wrong layout.
 */
public final class CopybookParser {

    private static final Pattern REPEAT = Pattern.compile("([SXV9])(?:\\((\\d+)\\))?");
    private static final Set<String> PACKED = Set.of("COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL");
    private static final Set<String> IGNORED = Set.of("USAGE", "IS", "DISPLAY", "TIMES");
    private static final Set<String> UNSUPPORTED = Set.of(
            "COMP", "COMP-1", "COMP-2", "COMP-4", "COMP-5", "COMPUTATIONAL", "BINARY",
            "REDEFINES", "SIGN", "LEADING", "TRAILING", "SEPARATE", "SYNC", "SYNCHRONIZED",
            "JUSTIFIED", "JUST", "DEPENDING", "INDEXED", "POINTER", "INDEX", "RENAMES");

    private CopybookParser() {
    }

    public static CopybookLayout parse(String name, String source) {
        List<FieldSpec> fields = new ArrayList<>();
        int offset = 0;
        for (String statement : statements(source)) {
            String[] tokens = statement.trim().split("\\s+");
            if (tokens.length < 2 || !tokens[0].matches("\\d{1,2}")) {
                throw new IllegalArgumentException(name + ": cannot parse '" + statement.trim() + "'");
            }
            int level = Integer.parseInt(tokens[0]);
            if (level == 88) {
                continue;
            }
            if (level == 66) {
                throw new IllegalArgumentException(name + ": level 66 is not supported");
            }
            String itemName = tokens[1].toUpperCase();
            String picture = null;
            boolean packed = false;
            int occurs = 1;
            for (int i = 2; i < tokens.length; i++) {
                String token = tokens[i].toUpperCase();
                if (token.equals("PIC") || token.equals("PICTURE")) {
                    i++;
                    if (i < tokens.length && tokens[i].equalsIgnoreCase("IS")) {
                        i++;
                    }
                    picture = tokens[i].toUpperCase();
                } else if (token.equals("OCCURS")) {
                    occurs = Integer.parseInt(tokens[++i]);
                } else if (PACKED.contains(token)) {
                    packed = true;
                } else if (token.equals("VALUE") || token.equals("VALUES")) {
                    break;
                } else if (UNSUPPORTED.contains(token)) {
                    throw new IllegalArgumentException(name + ": " + itemName + " uses unsupported clause " + token);
                } else if (!IGNORED.contains(token)) {
                    throw new IllegalArgumentException(name + ": " + itemName + " has unknown clause " + token);
                }
            }
            if (picture == null) {
                if (occurs != 1) {
                    throw new IllegalArgumentException(name + ": group OCCURS on " + itemName + " is not supported");
                }
                continue;
            }
            for (int index = 1; index <= occurs; index++) {
                FieldSpec field = elementary(name, itemName, picture, packed, offset);
                String fieldName = itemName.equals("FILLER")
                        ? "FILLER@" + offset
                        : occurs == 1 ? itemName : itemName + "[" + index + "]";
                fields.add(new FieldSpec(fieldName, field.offset(), field.length(), field.kind(),
                        field.digits(), field.scale(), field.signed()));
                offset += field.length();
            }
        }
        return new CopybookLayout(name.toUpperCase(), fields);
    }

    private static FieldSpec elementary(String copybook, String item, String picture, boolean packed, int offset) {
        String pic = picture;
        boolean signed = pic.startsWith("S");
        int integerDigits = 0;
        int fractionDigits = 0;
        int alphanumeric = 0;
        boolean afterV = false;
        Matcher matcher = REPEAT.matcher(pic);
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                break;
            }
            consumed = matcher.end();
            char symbol = matcher.group(1).charAt(0);
            int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            switch (symbol) {
                case 'S' -> {
                    if (matcher.start() != 0) {
                        throw new IllegalArgumentException(copybook + ": " + item + " misplaced S in " + picture);
                    }
                }
                case 'V' -> afterV = true;
                case 'X' -> alphanumeric += count;
                case '9' -> {
                    if (afterV) {
                        fractionDigits += count;
                    } else {
                        integerDigits += count;
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        if (consumed != pic.length()) {
            throw new IllegalArgumentException(copybook + ": " + item + " has unsupported picture " + picture);
        }
        if (alphanumeric > 0) {
            if (signed || afterV || integerDigits > 0 || packed) {
                throw new IllegalArgumentException(copybook + ": " + item + " mixes X and 9 in " + picture);
            }
            return new FieldSpec(item, offset, alphanumeric, FieldKind.ALPHANUMERIC, 0, 0, false);
        }
        int digits = integerDigits + fractionDigits;
        if (digits == 0 || digits > 31) {
            throw new IllegalArgumentException(copybook + ": " + item + " has invalid picture " + picture);
        }
        if (packed) {
            return new FieldSpec(item, offset, digits / 2 + 1, FieldKind.PACKED, digits, fractionDigits, signed);
        }
        return new FieldSpec(item, offset, digits, FieldKind.ZONED, digits, fractionDigits, signed);
    }

    /** Strips sequence/indicator areas and comment lines, then splits on terminating periods. */
    static List<String> statements(String source) {
        StringBuilder text = new StringBuilder();
        for (String line : source.split("\\R")) {
            if (line.length() > 6 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
                continue;
            }
            if (line.length() <= 7) {
                continue;
            }
            text.append(line, 7, Math.min(72, line.length())).append(' ');
        }
        return Arrays.stream(text.toString().split("\\.(?=\\s|$)"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }
}
