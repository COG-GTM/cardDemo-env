package com.carddemo.xferfee.contracts.replay;

/**
 * One in-process step of the XFRDAILY chain. Modules contribute stages as Spring beans; the
 * parity replay runs them in {@link #order()} and records the returned condition code under
 * {@link #step()} in rc.json.
 *
 * <p>Steps mirror the JCL: STEP010 = CBXFR01C (extract), STEP020 = XFERFEE (fee + posting),
 * STEP030 = CBXFR03C (recon report).
 */
public interface ReplayStage {

    String step();

    int order();

    int run(ReplayContext context) throws Exception;
}
