package com.vishwas.memory.hindsight;

import java.util.List;

/** A page of {@code GET /memories/list}. */
public record MemoryPage(List<MemoryUnit> items, int total, int limit, int offset) {

    public List<MemoryUnit> rows() {
        return items == null ? List.of() : items;
    }
}
