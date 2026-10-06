package com.carddemo.parity.candidate;

import com.carddemo.parity.engine.CaseInputs;
import com.carddemo.parity.engine.FeeRounding;
import java.nio.file.Path;

/**
 * {@code java -jar parity-console.jar --candidate --case <case> --out <dir> [--fixtures <dir>] [--break-it]}
 */
public final class CandidateCli {

    private CandidateCli() {
    }

    public static int run(String[] args) throws Exception {
        String caseName = null;
        Path out = null;
        Path fixtures = Path.of("fixtures", "xferfee");
        boolean breakIt = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--case" -> caseName = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--fixtures" -> fixtures = Path.of(args[++i]);
                case "--break-it" -> breakIt = true;
                default -> {
                }
            }
        }
        if (caseName == null || out == null) {
            System.err.println("usage: --candidate --case <case> --out <dir> [--fixtures <dir>] [--break-it]");
            return 2;
        }
        FeeRounding rounding = FeeRounding.of(breakIt);
        CandidateWriter.write(CaseInputs.load(fixtures, caseName), rounding, out);
        System.out.println("parity-console: wrote Java candidate for " + caseName + " (" + rounding + ") to " + out);
        return 0;
    }
}
