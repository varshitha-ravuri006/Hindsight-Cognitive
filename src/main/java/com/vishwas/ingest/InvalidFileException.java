package com.vishwas.ingest;

/** An uploaded file that cannot be used at all (wrong format, missing columns). Maps to HTTP 400. */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
