package com.carddemo.xferfee.live.engine;

import java.math.RoundingMode;

/**
 * Seam between the parity console and a transfer-fee implementation. The local JDBC engine is the
 * first implementation; the services from COG-1235..COG-1239 can replace it behind this interface.
 */
public interface TransferFeeEngine {

    void reset(EngineSeed seed);

    TransferResult process(DailyTransaction transaction);

    EngineState state();

    RoundingMode rounding();

    void setRounding(RoundingMode rounding);
}
