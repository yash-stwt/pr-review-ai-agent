package com.aicodereviewer.backend.ai.exception;

/**
 * Thrown when an AI provider call fails.
 * The {@code retryable} flag indicates whether the caller should retry.
 */
public class AiProviderException extends Exception {

    private final boolean retryable;

    public AiProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AiProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
