package com.aicodereviewer.backend.ai.exception;

/**
 * Thrown when all providers in the fallback chain are exhausted.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
