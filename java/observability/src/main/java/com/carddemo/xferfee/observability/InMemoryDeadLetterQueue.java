package com.carddemo.xferfee.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryDeadLetterQueue implements DeadLetterQueue {

    private final List<DeadLetterEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void put(DeadLetterEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<DeadLetterEntry> entries() {
        return List.copyOf(entries);
    }
}
