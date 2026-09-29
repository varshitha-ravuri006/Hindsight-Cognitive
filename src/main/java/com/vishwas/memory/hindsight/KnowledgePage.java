package com.vishwas.memory.hindsight;

import java.util.List;

/** {@code GET /knowledge-base/pages/{id}}: a page as frontmatter + markdown. */
public record KnowledgePage(String id, String name, String type, String description, List<String> tags,
                            String timestamp, String body, String markdown) {
}
