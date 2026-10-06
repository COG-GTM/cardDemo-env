package com.carddemo.observability;

/** Steps of the XFERFEEP procedure and the program each one runs. */
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
