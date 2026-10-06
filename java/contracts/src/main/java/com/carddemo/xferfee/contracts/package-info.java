/**
 * Frozen contract types for the xferfee port (COG-1234). Every module codes against these
 * names; do not rename them. Additive changes (new optional types, new enum constants) are fine.
 *
 * <p>Conventions: money and rates are {@link java.math.BigDecimal} serialized as JSON strings;
 * dates are {@link java.time.LocalDate} serialized as {@code yyyy-MM-dd} strings; book ids are
 * trimmed (the CHAR(10)/PIC X(10) padding is applied only at legacy edges).
 */
package com.carddemo.xferfee.contracts;
