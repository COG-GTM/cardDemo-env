package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Seeds a {@link FeeSchedule} from a fixture's {@code db2_before} and dumps it as {@code db2_after}. */
public final class FeeRuleSnapshots {

    public static final String TABLE_FILE = "CTL_XFER_PARM.csv";

    private FeeRuleSnapshots() {
    }

    public static List<FeeRule> seed(FeeSchedule schedule, Path db2BeforeDir) throws IOException {
        List<FeeRule> rules = FeeRuleCsv.read(db2BeforeDir.resolve(TABLE_FILE));
        schedule.seed(rules);
        return rules;
    }

    public static Path dump(FeeSchedule schedule, Path db2AfterDir) throws IOException {
        Path target = db2AfterDir.resolve(TABLE_FILE);
        FeeRuleCsv.write(target, schedule.rules());
        return target;
    }
}
