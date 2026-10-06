package com.carddemo.xferfee.legacy.codec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Encoding defaults used for fields whose original representation is unknown (fresh records).
 *
 * @param charset          character set for PIC X and zoned digits
 * @param fillerByte       byte written into FILLER areas
 * @param zonedSignStyle   sign style for signed zoned items
 */
public record CodecOptions(Charset charset, byte fillerByte, SignStyle zonedSignStyle) {

    /**
     * What the GnuCOBOL chain writes for a record built field by field in the FD buffer: FILLER
     * left as LOW-VALUES and computed signs in native ASCII form.
     */
    public static final CodecOptions GNUCOBOL_OUTPUT =
            new CodecOptions(StandardCharsets.ISO_8859_1, (byte) 0x00, SignStyle.NATIVE);

    /**
     * Space-filled records with overpunch signs, matching {@code tools/fixtures/gen_fixtures.py}
     * and {@code tools/parity/copybook.py}.
     */
    public static final CodecOptions OVERPUNCH_SPACES =
            new CodecOptions(StandardCharsets.ISO_8859_1, (byte) 0x20, SignStyle.OVERPUNCH);

    /** z/OS EBCDIC (code page 037) datasets, e.g. {@code fixtures/carddemo/ebcdic}. */
    public static final CodecOptions EBCDIC_037 =
            new CodecOptions(Charset.forName("IBM037"), (byte) 0x40, SignStyle.OVERPUNCH);

    public CodecOptions {
        if (zonedSignStyle != SignStyle.OVERPUNCH && zonedSignStyle != SignStyle.NATIVE) {
            throw new IllegalArgumentException("zoned sign style must be OVERPUNCH or NATIVE");
        }
    }
}
