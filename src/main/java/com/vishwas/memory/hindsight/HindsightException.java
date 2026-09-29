package com.vishwas.memory.hindsight;

/** Any failure talking to Hindsight. Callers catch it and degrade: memory never crashes a request. */
public class HindsightException extends RuntimeException {

    private final int status;

    public HindsightException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean notFound() {
        return status == 404;
    }
}
