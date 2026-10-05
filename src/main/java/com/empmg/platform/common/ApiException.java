package com.empmg.platform.common;

/** Domain error with a user-friendly message and an HTTP status. */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public ApiException(String message) {
        this(message, 400);
    }

    public int getStatus() {
        return status;
    }
}
