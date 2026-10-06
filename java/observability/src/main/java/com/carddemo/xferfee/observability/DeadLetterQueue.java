package com.carddemo.xferfee.observability;

import java.util.List;

public interface DeadLetterQueue {

    void put(DeadLetterEntry entry);

    List<DeadLetterEntry> entries();
}
