package com.carddemo.xferfee.cutover;

/** Verdict of one shadow run as written by {@code tools/shadow/shadow_run.py}. */
public enum ShadowStatus {
    PASS,
    FAIL,
    ERROR,
    UNKNOWN;

    static ShadowStatus parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
