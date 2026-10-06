package org.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/**
 * Computes the transfer fee for one transfer under an already-resolved fee rule (XFERFEE 2100-POST-ONE).
 * Rule lookup, and failing the run when no rule matches, are the caller's job.
 */
public interface FeePolicy {

    FeeResult apply(BigDecimal amount, FeeRule rule);
}
