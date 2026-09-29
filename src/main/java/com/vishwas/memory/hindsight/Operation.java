package com.vishwas.memory.hindsight;

/**
 * An async Hindsight operation. The list endpoint names its fields id/task_type and the single read uses
 * operation_id/operation_type; both are mapped and {@link #key()}/{@link #kind()} unify them.
 */
public record Operation(String id, String operationId, String taskType, String operationType, Integer itemsCount,
                        String status, String createdAt, String completedAt, String errorMessage) {

    public String key() {
        return id != null ? id : operationId;
    }

    public String kind() {
        return taskType != null ? taskType : operationType;
    }

    public boolean busy() {
        return "pending".equals(status) || "processing".equals(status);
    }

    public boolean failed() {
        return "failed".equals(status) || "cancelled".equals(status);
    }
}
