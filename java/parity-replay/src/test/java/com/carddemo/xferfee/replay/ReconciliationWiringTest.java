package com.carddemo.xferfee.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.port.Reconciliation;
import com.carddemo.xferfee.reconciliation.LegacyReconStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReconciliationWiringTest {

    @Autowired
    Reconciliation reconciliation;

    @Test
    void replayPicksUpTheReconciliationServiceStage() {
        assertThat(reconciliation).isInstanceOf(LegacyReconStep.class);
    }
}
