package com.carddemo.legacy.copybook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses the fixed-format copybook subset used by the transfer-fee chain: elementary items with
 * {@code PIC X}, {@code PIC [S]9[V9]} (DISPLAY or COMP-3) and {@code OCCURS n}. Group items without
 * a picture are structural only and contribute no storage of their own.
 */
public final class CopybookParser {
  private static final Pattern ITEM =
      Pattern.compile(
          "^\\s*(\\d{1,2})\\s+([A-Z0-9-]+)(?:\\s+PIC(?:TURE)?\\s+(?:IS\\s+)?([SXV9()0-9]+))?"
              + "(?:\\s+(?:USAGE\\s+(?:IS\\s+)?)?(COMP-3|COMPUTATIONAL-3|COMP3|DISPLAY))?"
              + "(?:\\s+OCCURS\\s+(\\d+)(?:\\s+TIMES)?)?\\s*\\.?\\s*$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PICTURE_SYMBOL = Pattern.compile("([X9])(?:\\((\\d+)\\))?");

  private final Path copybookDirectory;
  private final Map<String, CopybookLayout> cache = new ConcurrentHashMap<>();

  public CopybookParser(Path copybookDirectory) {
    this.copybookDirectory = copybookDirectory;
  }

  public Path copybookDirectory() {
    return copybookDirectory;
  }

  /** Loads a copybook by member name (case-insensitive, {@code .cpy} extension). */
  public CopybookLayout layout(String member) {
    return cache.computeIfAbsent(member.toUpperCase(Locale.ROOT), this::load);
  }

  private CopybookLayout load(String member) {
    try (Stream<Path> files = Files.list(copybookDirectory)) {
      Path path =
          files
              .filter(p -> p.getFileName().toString().equalsIgnoreCase(member + ".cpy"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "copybook " + member + " not found in " + copybookDirectory));
      return parse(member, Files.readAllLines(path, StandardCharsets.ISO_8859_1));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read copybook " + member, e);
    }
  }

  /** Parses copybook source lines (fixed-format columns 7-72). */
  public static CopybookLayout parse(String member, List<String> lines) {
    List<CopybookField> fields = new ArrayList<>();
    int offset = 0;
    for (String raw : lines) {
      String line = sourceArea(raw);
      if (line.isBlank()) {
        continue;
      }
      Matcher m = ITEM.matcher(line);
      if (!m.matches()) {
        if (line.toUpperCase(Locale.ROOT).contains("REDEFINES")) {
          throw new IllegalArgumentException(member + ": REDEFINES is not supported: " + raw.trim());
        }
        continue;
      }
      String picture = m.group(3);
      if (picture == null) {
        continue;
      }
      String name = m.group(2).toUpperCase(Locale.ROOT);
      String usage = m.group(4) == null ? "DISPLAY" : m.group(4).toUpperCase(Locale.ROOT);
      int occurs = m.group(5) == null ? 1 : Integer.parseInt(m.group(5));
      CopybookField template = elementary(member, name, picture.toUpperCase(Locale.ROOT), usage);
      for (int i = 1; i <= occurs; i++) {
        String occurrence = occurs == 1 || template.isFiller() ? name : name + "[" + i + "]";
        fields.add(
            new CopybookField(
                occurrence,
                offset,
                template.length(),
                template.kind(),
                template.digits(),
                template.scale(),
                template.signed()));
        offset += template.length();
      }
    }
    if (fields.isEmpty()) {
      throw new IllegalArgumentException(member + ": no elementary items found");
    }
    return new CopybookLayout(member, fields);
  }

  private static String sourceArea(String raw) {
    if (raw.length() > 6 && (raw.charAt(6) == '*' || raw.charAt(6) == '/')) {
      return "";
    }
    String area = raw.length() > 72 ? raw.substring(0, 72) : raw;
    return area.length() > 6 ? area.substring(6) : "";
  }

  private static CopybookField elementary(String member, String name, String picture, String usage) {
    boolean signed = picture.startsWith("S");
    String body = signed ? picture.substring(1) : picture;
    if (body.indexOf('X') >= 0) {
      if (signed || body.indexOf('9') >= 0 || body.indexOf('V') >= 0) {
        throw new IllegalArgumentException(member + ": unsupported picture for " + name + ": " + picture);
      }
      int chars = count(body);
      return new CopybookField(name, 0, chars, FieldKind.ALPHANUMERIC, chars, 0, false);
    }
    int v = body.indexOf('V');
    int integer = count(v >= 0 ? body.substring(0, v) : body);
    int scale = v >= 0 ? count(body.substring(v + 1)) : 0;
    int digits = integer + scale;
    if (digits == 0 || digits > 18) {
      throw new IllegalArgumentException(member + ": unsupported digit count for " + name + ": " + picture);
    }
    boolean packed = usage.startsWith("COMP");
    int length = packed ? digits / 2 + 1 : digits;
    return new CopybookField(name, 0, length, packed ? FieldKind.PACKED : FieldKind.ZONED, digits, scale, signed);
  }

  private static int count(String picture) {
    Matcher m = PICTURE_SYMBOL.matcher(picture);
    int total = 0;
    int consumed = 0;
    while (m.find()) {
      if (m.start() != consumed) {
        throw new IllegalArgumentException("unsupported picture symbols: " + picture);
      }
      total += m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
      consumed = m.end();
    }
    if (consumed != picture.length()) {
      throw new IllegalArgumentException("unsupported picture symbols: " + picture);
    }
    return total;
  }
}
