package com.vishwas.memory.hindsight;

import java.util.List;

/** A Hindsight mental model: a curated summary built by reflecting over the bank, refreshed on demand. */
public record MentalModel(String id, String name, String sourceQuery, String content, List<String> tags,
                          String lastRefreshedAt, String lastRefreshFailedAt, Boolean isStale) {
}
