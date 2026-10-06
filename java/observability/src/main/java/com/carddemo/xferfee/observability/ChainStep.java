package com.carddemo.xferfee.observability;

/** The three XFRDAILY / XFERFEEP job steps and the COBOL program each one runs. */
public enum ChainStep {
    STEP010("CBXFR01C"),
    STEP020("XFERFEE"),
    STEP030("CBXFR03C");

    private final String program;

    ChainStep(String program) {
        this.program = program;
    }

    public String program() {
        return program;
    }
}
