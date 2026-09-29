package com.vishwas.memory.hindsight;

import java.util.List;

/** A folder or page in the bank's knowledge-base tree. */
public record KnowledgeNode(String id, String kind, String name, String parentId, String mentalModelId,
                            String description, List<String> tags, String timestamp, Boolean isStale,
                            List<KnowledgeNode> children) {

    public List<KnowledgeNode> childNodes() {
        return children == null ? List.of() : children;
    }
}
