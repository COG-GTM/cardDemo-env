package com.carddemo.xferfee.services.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bulk replace of CTL_XFER_PARM; off unless {@code xferfee.fee-schedule.admin-api=true} (no auth layer yet). */
@RestController
@RequestMapping("/api/fee-rules")
@ConditionalOnProperty(name = "xferfee.fee-schedule.admin-api", havingValue = "true")
public class FeeRuleAdminController {

    private final FeeSchedule schedule;

    public FeeRuleAdminController(FeeSchedule schedule) {
        this.schedule = schedule;
    }

    @PutMapping
    public List<FeeRule> replace(@RequestBody List<FeeRule> rules) {
        schedule.seed(rules);
        return schedule.rules();
    }
}
