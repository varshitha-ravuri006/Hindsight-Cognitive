package com.vishwas.memory.hindsight;

import java.util.List;
import java.util.Map;

/** A file for {@code POST /files/retain} plus its per-file {@code files_metadata} entry. */
public record FileUpload(String filename, String contentType, byte[] bytes,
                         String documentId, String context, String timestamp,
                         List<String> tags, Map<String, String> metadata) {
}
