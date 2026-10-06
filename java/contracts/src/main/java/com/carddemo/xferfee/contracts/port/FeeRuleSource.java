package com.carddemo.xferfee.contracts.port;

import com.carddemo.xferfee.contracts.FeeRule;
import java.util.List;

/** The CTL_XFER_PARM rows visible to the run (the harness supplies db2_before). */
public interface FeeRuleSource {

    List<FeeRule> rules();
}
