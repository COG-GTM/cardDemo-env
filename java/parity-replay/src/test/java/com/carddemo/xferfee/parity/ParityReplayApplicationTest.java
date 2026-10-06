package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ParityReplayApplicationTest {

    @Autowired
    ReplayEngine engine;

    @Test
    void contextStartsWithoutAnyModuleImplementation() {
        assertThat(engine).isNotNull();
    }
}
