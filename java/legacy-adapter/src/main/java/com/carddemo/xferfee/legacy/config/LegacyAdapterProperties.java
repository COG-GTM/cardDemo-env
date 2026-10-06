package com.carddemo.xferfee.legacy.config;

import com.carddemo.xferfee.legacy.codec.CodecOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("carddemo.legacy-adapter")
public class LegacyAdapterProperties {

    public enum Encoding {
        /** Local GnuCOBOL estate: ASCII, LOW-VALUE fillers, native signs on output. */
        ASCII,
        /** z/OS datasets transferred in binary: EBCDIC code page 037. */
        EBCDIC
    }

    private Encoding encoding = Encoding.ASCII;

    public Encoding getEncoding() {
        return encoding;
    }

    public void setEncoding(Encoding encoding) {
        this.encoding = encoding;
    }

    public CodecOptions codecOptions() {
        return encoding == Encoding.EBCDIC ? CodecOptions.EBCDIC_037 : CodecOptions.GNUCOBOL_OUTPUT;
    }
}
