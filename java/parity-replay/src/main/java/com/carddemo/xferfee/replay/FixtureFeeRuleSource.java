package com.carddemo.xferfee.replay;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.port.FeeRuleSource;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/** CTL_XFER_PARM as captured in the case's {@code db2_before}, read from {@code --in}. */
@Component
class FixtureFeeRuleSource implements FeeRuleSource {

    static final String FILE = "CTL_XFER_PARM.jsonl";

    private final ApplicationArguments args;
    private List<FeeRule> rules;

    FixtureFeeRuleSource(ApplicationArguments args) {
        this.args = args;
    }

    @Override
    public synchronized List<FeeRule> rules() {
        if (rules == null) {
            rules = List.copyOf(JsonLines.read(ReplayOptions.from(args).in().resolve(FILE), FeeRule.class));
        }
        return rules;
    }
}
